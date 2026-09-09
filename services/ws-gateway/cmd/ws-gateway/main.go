package main

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
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
	port            string
	shutdownTimeout time.Duration
	logLevel        slog.Level
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

func main() {
	cfg := loadConfig()
	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: cfg.logLevel}))
	handler := newHandler(logger)
	server := &http.Server{
		Addr:              ":" + cfg.port,
		Handler:           handler,
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
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health/live", healthHandler("liveness"))
	mux.HandleFunc("GET /health/ready", healthHandler("readiness"))
	mux.HandleFunc("GET /health", healthHandler("liveness"))

	return requestIDMiddleware(loggingMiddleware(logger, mux))
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
		port:            envOrDefault("PORT", "8081"),
		shutdownTimeout: shutdownTimeout,
		logLevel:        parseLogLevel(os.Getenv("LOG_LEVEL")),
	}
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
