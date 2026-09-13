package main

import (
	"bufio"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"
)

const requestIDHeader = "X-Request-ID"

type requestIDContextKey struct{}

type config struct {
	port                     string
	shutdownTimeout          time.Duration
	logLevel                 slog.Level
	apiBaseURL               string
	apiTimeout               time.Duration
	redisAddr                string
	redisPassword            string
	redisDB                  int
	redisKeyPrefix           string
	connectionMetadataTTL    time.Duration
	jwtIssuer                string
	jwtPublicKeyBase64       string
	jwtPublicKeyFile         string
	jwtJwksURL               string
	allowedOrigins           []string
	heartbeatInterval        time.Duration
	heartbeatTimeout         time.Duration
	writeTimeout             time.Duration
	sendQueueSize            int
	maxMessageBytes          int64
}

type probeResponse struct {
	Status    string    `json:"status"`
	Probe     string    `json:"probe"`
	Timestamp time.Time `json:"timestamp"`
}

type responseWriter struct {
	http.ResponseWriter
	status int
}

func (writer *responseWriter) WriteHeader(status int) {
	writer.status = status
	writer.ResponseWriter.WriteHeader(status)
}

func (writer *responseWriter) Write(body []byte) (int, error) {
	if writer.status == 0 {
		writer.WriteHeader(http.StatusOK)
	}
	return writer.ResponseWriter.Write(body)
}

func (writer *responseWriter) Hijack() (net.Conn, *bufio.ReadWriter, error) {
	hijacker, ok := writer.ResponseWriter.(http.Hijacker)
	if !ok {
		return nil, nil, errors.New("underlying response writer does not support hijacking")
	}
	return hijacker.Hijack()
}

func (writer *responseWriter) Flush() {
	if flusher, ok := writer.ResponseWriter.(http.Flusher); ok {
		flusher.Flush()
	}
}

func main() {
	cfg := loadConfig()
	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: cfg.logLevel}))
	runtime := newGateway(cfg, logger)
	server := &http.Server{
		Addr:              ":" + cfg.port,
		Handler:           runtime.handler(),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      15 * time.Second,
		IdleTimeout:       60 * time.Second,
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	serverErrors := make(chan error, 1)
	go func() {
		logger.Info("server_started", "service", "ws-gateway", "address", server.Addr)
		if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			serverErrors <- err
		}
	}()

	select {
	case <-ctx.Done():
		shutdownCtx, cancel := context.WithTimeout(context.Background(), cfg.shutdownTimeout)
		defer cancel()
		logger.Info("server_shutdown_started", "service", "ws-gateway")
		if err := runtime.close(shutdownCtx); err != nil {
			logger.Error("websocket_shutdown_failed", "service", "ws-gateway", "error", err)
			os.Exit(1)
		}
		if err := server.Shutdown(shutdownCtx); err != nil {
			logger.Error("server_shutdown_failed", "service", "ws-gateway", "error", err)
			os.Exit(1)
		}
		logger.Info("server_shutdown_completed", "service", "ws-gateway")
	case err := <-serverErrors:
		logger.Error("server_failed", "service", "ws-gateway", "error", err)
		os.Exit(1)
	}
}

func newHandler(logger *slog.Logger) http.Handler {
	cfg := loadConfig()
	return newGatewayWithDependencies(cfg, logger, "test-handler", newMemoryMetadataStore(),
		newAPIMessageRouter(cfg), newAPISyncProvider(cfg), noopCommandRouter{}, newAccessTokenValidator(cfg)).handler()
}

func healthHandler(probe string) http.HandlerFunc {
	return func(writer http.ResponseWriter, _ *http.Request) {
		writer.Header().Set("Content-Type", "application/json")
		writer.WriteHeader(http.StatusOK)
		_ = json.NewEncoder(writer).Encode(probeResponse{
			Status:    "UP",
			Probe:     probe,
			Timestamp: time.Now().UTC(),
		})
	}
}

func requestIDMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		requestID := request.Header.Get(requestIDHeader)
		if !isSafeRequestID(requestID) {
			requestID = newRequestID()
		}

		writer.Header().Set(requestIDHeader, requestID)
		request = request.WithContext(context.WithValue(request.Context(), requestIDContextKey{}, requestID))
		next.ServeHTTP(writer, request)
	})
}

func loggingMiddleware(logger *slog.Logger, next http.Handler) http.Handler {
	return http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		started := time.Now()
		wrapped := &responseWriter{ResponseWriter: writer}
		next.ServeHTTP(wrapped, request)

		requestID, _ := request.Context().Value(requestIDContextKey{}).(string)
		status := wrapped.status
		if status == 0 {
			status = http.StatusOK
		}
		logger.Info("http_request_completed",
			"service", "ws-gateway",
			"method", request.Method,
			"path", request.URL.Path,
			"status", status,
			"duration_ms", time.Since(started).Milliseconds(),
			"request_id", requestID,
		)
	})
}

func loadConfig() config {
	shutdownTimeout := 10 * time.Second
	if raw := os.Getenv("SHUTDOWN_TIMEOUT"); raw != "" {
		if parsed, err := time.ParseDuration(raw); err == nil && parsed > 0 {
			shutdownTimeout = parsed
		}
	}

	return config{
		port:                  envOrDefault("PORT", "8081"),
		shutdownTimeout:       shutdownTimeout,
		logLevel:              parseLogLevel(os.Getenv("LOG_LEVEL")),
		apiBaseURL:            envOrDefault("API_BASE_URL", "http://localhost:8080"),
		apiTimeout:            parseDurationEnv("API_TIMEOUT", 10*time.Second),
		redisAddr:             envOrDefault("REDIS_ADDR", "localhost:6379"),
		redisPassword:         os.Getenv("REDIS_PASSWORD"),
		redisDB:               parseIntEnv("REDIS_DB", 0),
		redisKeyPrefix:        envOrDefault("REDIS_KEY_PREFIX", "uvya:ws"),
		connectionMetadataTTL: parseDurationEnv("CONNECTION_METADATA_TTL", 2*time.Minute),
		jwtIssuer:             envOrDefaultFirst("UVYA_JWT_ISSUER", "JWT_ISSUER", "http://localhost:8080"),
		jwtPublicKeyBase64:    envOrDefaultFirst("UVYA_JWT_PUBLIC_KEY_BASE64", "JWT_PUBLIC_KEY_BASE64", ""),
		jwtPublicKeyFile:      envOrDefault("JWT_PUBLIC_KEY_FILE", ""),
		jwtJwksURL:            envOrDefault("JWT_JWKS_URL", "http://localhost:8080/.well-known/jwks.json"),
		allowedOrigins:        splitCSV(os.Getenv("ALLOWED_ORIGINS")),
		heartbeatInterval:     parseDurationEnv("HEARTBEAT_INTERVAL", 30*time.Second),
		heartbeatTimeout:      parseDurationEnv("HEARTBEAT_TIMEOUT", 90*time.Second),
		writeTimeout:          parseDurationEnv("WRITE_TIMEOUT", 10*time.Second),
		sendQueueSize:         parseIntEnv("SEND_QUEUE_SIZE", 256),
		maxMessageBytes:       int64(parseIntEnv("MAX_MESSAGE_BYTES", 1<<20)),
	}
}

func parseDurationEnv(name string, fallback time.Duration) time.Duration {
	value := os.Getenv(name)
	if parsed, err := time.ParseDuration(value); err == nil && parsed > 0 {
		return parsed
	}
	return fallback
}

func parseIntEnv(name string, fallback int) int {
	if parsed, err := strconv.Atoi(os.Getenv(name)); err == nil && parsed > 0 {
		return parsed
	}
	return fallback
}

func envOrDefaultFirst(primary, secondary, fallback string) string {
	if value := os.Getenv(primary); value != "" {
		return value
	}
	return envOrDefault(secondary, fallback)
}

func splitCSV(value string) []string {
	if strings.TrimSpace(value) == "" {
		return nil
	}
	values := make([]string, 0)
	for _, item := range strings.Split(value, ",") {
		if value := strings.TrimSpace(item); value != "" {
			values = append(values, value)
		}
	}
	return values
}

func parseLogLevel(value string) slog.Level {
	switch strings.ToLower(value) {
	case "debug":
		return slog.LevelDebug
	case "warn", "warning":
		return slog.LevelWarn
	case "error":
		return slog.LevelError
	default:
		return slog.LevelInfo
	}
}

func envOrDefault(name string, fallback string) string {
	if value := os.Getenv(name); value != "" {
		return value
	}
	return fallback
}

func isSafeRequestID(value string) bool {
	if value == "" || len(value) > 128 {
		return false
	}
	for _, character := range value {
		if (character < 'a' || character > 'z') &&
			(character < 'A' || character > 'Z') &&
			(character < '0' || character > '9') &&
			character != '.' && character != '_' && character != ':' && character != '-' {
			return false
		}
	}
	return true
}

func newRequestID() string {
	bytes := make([]byte, 16)
	if _, err := io.ReadFull(rand.Reader, bytes); err != nil {
		return strconv.FormatInt(time.Now().UnixNano(), 36)
	}
	return hex.EncodeToString(bytes)
}
