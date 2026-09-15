package main

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

type gateway struct {
	cfg             config
	logger          *slog.Logger
	gatewayID       string
	validator       *accessTokenValidator
	registry        *connectionRegistry
	metadata        metadataStore
	messageRouter   messageRouter
	syncProvider    syncProvider
	commandRouter   commandRouter
	metrics         *gatewayMetrics
	realtime        *realtimeStateManager
	closeOnce       sync.Once
}

var websocketUpgrader = websocket.Upgrader{
	ReadBufferSize:  4096,
	WriteBufferSize: 4096,
	EnableCompression: true,
}

func newGateway(cfg config, logger *slog.Logger) *gateway {
	gatewayID := newRequestID()
	metadata := newRedisMetadataStore(cfg)
	gateway := newGatewayWithDependencies(cfg, logger, gatewayID, metadata, newAPIMessageRouter(cfg),
		newAPISyncProvider(cfg), newAPICommandRouter(cfg), newAccessTokenValidator(cfg))
	if err := metadata.Subscribe(context.Background(), gateway.handleRoute); err != nil {
		logger.Warn("redis_route_subscription_failed", "error", err)
	}
	gateway.realtime.setAuthorizer(newAPIRealtimeAuthorizer(cfg))
	return gateway
}

func newGatewayWithDependencies(cfg config, logger *slog.Logger, gatewayID string, metadata metadataStore,
	messageRouter messageRouter, syncProvider syncProvider, commandRouter commandRouter,
	validator *accessTokenValidator) *gateway {
	gateway := &gateway{
		cfg:           cfg,
		logger:        logger,
		gatewayID:     gatewayID,
		validator:     validator,
		registry:      newConnectionRegistry(),
		metadata:      metadata,
		messageRouter: messageRouter,
		syncProvider:  syncProvider,
		commandRouter: commandRouter,
		metrics:       newGatewayMetrics(gatewayID),
	}
	gateway.realtime = newRealtimeStateManager(gateway, metadataStoreAsEphemeral(metadata))
	return gateway
}

func metadataStoreAsEphemeral(metadata metadataStore) ephemeralStore {
	store, _ := metadata.(ephemeralStore)
	return store
}

func (gateway *gateway) handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health/live", healthHandler("liveness"))
	mux.HandleFunc("GET /health/ready", gateway.readinessHandler)
	mux.HandleFunc("GET /health", healthHandler("liveness"))
	mux.HandleFunc("GET /metrics", gateway.metrics.handler)
	mux.HandleFunc("GET /ws", gateway.websocketHandler)
	mux.HandleFunc("GET /v1/ws", gateway.websocketHandler)
	return requestIDMiddleware(loggingMiddleware(gateway.logger, mux))
}

func (gateway *gateway) readinessHandler(writer http.ResponseWriter, request *http.Request) {
	ctx, cancel := context.WithTimeout(request.Context(), time.Second)
	defer cancel()
	if err := gateway.metadata.Ready(ctx); err != nil {
		writeHTTPError(writer, http.StatusServiceUnavailable, "realtime metadata store is unavailable")
		return
	}
	healthHandler("readiness")(writer, request)
}

func (gateway *gateway) websocketHandler(writer http.ResponseWriter, request *http.Request) {
	if !gateway.originAllowed(request) {
		writeHTTPError(writer, http.StatusForbidden, "origin is not allowed")
		return
	}
	authenticated, err := gateway.validator.Validate(extractAccessToken(request))
	if err != nil {
		status := http.StatusUnauthorized
		if errors.Is(err, errAuthUnavailable) {
			status = http.StatusServiceUnavailable
		}
		writer.Header().Set("WWW-Authenticate", "Bearer")
		writeHTTPError(writer, status, err.Error())
		return
	}
	deviceID := request.Header.Get("X-Device-ID")
	if deviceID == "" {
		deviceID = request.URL.Query().Get("deviceId")
	}
	if deviceID != authenticated.DeviceID {
		writeHTTPError(writer, http.StatusUnauthorized, "deviceId does not match the access token")
		return
	}

	upgrader := websocketUpgrader
	upgrader.CheckOrigin = func(_ *http.Request) bool { return true }
	ws, err := upgrader.Upgrade(writer, request, nil)
	if err != nil {
		return
	}
	connection := newConnection(ws, gateway, authenticated)
	metadata := connectionMetadata{UserID: connection.userID, DeviceID: connection.deviceID,
		SessionID: connection.sessionID, ConnectionID: connection.connectionID, GatewayID: gateway.gatewayID,
		LastSeen: time.Now().UTC()}
	if err := gateway.metadata.Register(request.Context(), metadata); err != nil {
		// Redis is required for cross-gateway routing, but not for local message
		// persistence. Keep the authenticated local socket alive in degraded mode.
		gateway.metrics.redisDegraded.Add(1)
		gateway.logger.Warn("redis_connection_registration_failed_local_degraded", "error", err)
	}
	previous := gateway.registry.register(connection)
	gateway.metrics.connections.Store(int64(gateway.registry.count()))
	if previous != nil {
		gateway.metrics.reconnects.Add(1)
		gateway.realtime.transferSubscriptions(previous, connection)
		gateway.disconnect(previous, 4001, "device reconnected")
	}
	gateway.realtime.connectionOnline(connection)
	connection.start()
}

func (gateway *gateway) originAllowed(request *http.Request) bool {
	if len(gateway.cfg.allowedOrigins) == 0 || request.Header.Get("Origin") == "" {
		return true
	}
	origin := request.Header.Get("Origin")
	for _, allowed := range gateway.cfg.allowedOrigins {
		if strings.TrimRight(allowed, "/") == strings.TrimRight(origin, "/") {
			return true
		}
	}
	return false
}

func (gateway *gateway) handleClientMessage(connection *connection, data []byte) {
	message, err := decodeClientEnvelope(data)
	if err != nil {
		connection.enqueue(errorEnvelope("", "invalid_message", err.Error()))
		return
	}
	switch message.Type {
	case messageSend:
		gateway.handleMessageSend(connection, message)
	case syncResume:
		gateway.handleSyncResume(connection, message)
	case heartbeat:
		gateway.realtime.touch(connection)
		connection.enqueue(marshalEvent(outboundEnvelope{Type: heartbeatAck, RequestID: message.RequestID}))
	case presenceSubscribe:
		if err := gateway.realtime.subscribe(connection, message); err != nil {
			gateway.realtime.degraded("presence", err)
			connection.enqueue(errorEnvelope(message.RequestID, "presence_unavailable", "presence subscriptions are unavailable"))
		}
	case presenceUnsubscribe:
		if err := gateway.realtime.unsubscribe(connection, message); err != nil {
			gateway.realtime.degraded("presence", err)
			connection.enqueue(errorEnvelope(message.RequestID, "presence_unavailable", "presence subscriptions are unavailable"))
		}
	case presenceSet, presenceUpdated:
		if err := gateway.realtime.setPresence(connection, message); err != nil {
			connection.enqueue(errorEnvelope(message.RequestID, "presence_unavailable", "presence is currently unavailable"))
		}
	case typingStart:
		if err := gateway.realtime.startTyping(connection, message); err != nil {
			connection.enqueue(errorEnvelope(message.RequestID, "typing_unavailable", "typing indicators are currently unavailable"))
		}
	case typingStop:
		if err := gateway.realtime.stopTyping(connection, message); err != nil {
			connection.enqueue(errorEnvelope(message.RequestID, "typing_unavailable", "typing indicators are currently unavailable"))
		}
	case messageDelivered, messageRead:
		if err := gateway.commandRouter.Handle(context.Background(), connection.auth(), message); err != nil {
			connection.enqueue(errorEnvelope(message.RequestID, "command_failed", "unable to handle command"))
		}
	default:
		connection.enqueue(errorEnvelope(message.RequestID, "unsupported_type", "unsupported message type"))
	}
}

func (gateway *gateway) handleMessageSend(connection *connection, message clientEnvelope) {
	if message.RequestID == "" || !isUUID(message.ChatID) || !isUUID(message.ClientMessageID) {
		connection.enqueue(errorEnvelope(message.RequestID, "invalid_message_send", "requestId, chatId, and clientMessageId are required"))
		return
	}
	if len(message.Payload) == 0 || string(message.Payload) == "null" {
		connection.enqueue(errorEnvelope(message.RequestID, "invalid_message_send", "payload is required"))
		return
	}
	persisted, err := gateway.messageRouter.Persist(context.Background(), connection.auth(), message)
	if err != nil {
		gateway.metrics.messagesFailed.Add(1)
		connection.enqueue(errorEnvelope(message.RequestID, "message_not_persisted", err.Error()))
		return
	}
	connection.enqueue(marshalEvent(outboundEnvelope{Type: messagePersisted, RequestID: message.RequestID,
		MessageID: persisted.MessageID, ChatID: persisted.ChatID, Sequence: persisted.Sequence}))
}

func (gateway *gateway) handleSyncResume(connection *connection, message clientEnvelope) {
	resume, err := validateSyncRequest(message, connection.deviceID)
	if err != nil {
		connection.enqueue(errorEnvelope(message.RequestID, "invalid_resume", err.Error()))
		return
	}
	gateway.metrics.resumes.Add(1)
	delta, err := gateway.syncProvider.Resume(context.Background(), connection.auth(), resume)
	if err != nil {
		connection.enqueue(errorEnvelope(message.RequestID, "sync_failed", "unable to resume synchronization"))
		return
	}
	if delta.Type == "" {
		delta.Type = syncDelta
	}
	if delta.RequestID == "" {
		delta.RequestID = resume.RequestID
	}
	connection.enqueue(marshalEvent(delta))
}

func (gateway *gateway) routeToUser(userID string, event outboundEnvelope) int {
	if !isUUID(userID) {
		return 0
	}
	encoded := marshalEvent(event)
	delivered := gateway.routeLocal(routeEnvelope{TargetUserID: userID, OriginGateway: gateway.gatewayID,
		Event: encoded})
	gateway.publishRoute(routeEnvelope{TargetUserID: userID, OriginGateway: gateway.gatewayID, Event: encoded})
	return delivered
}

func (gateway *gateway) routeToDevice(deviceID string, event outboundEnvelope) int {
	if !isUUID(deviceID) {
		return 0
	}
	encoded := marshalEvent(event)
	delivered := gateway.routeLocal(routeEnvelope{TargetDeviceID: deviceID, OriginGateway: gateway.gatewayID,
		Event: encoded})
	gateway.publishRoute(routeEnvelope{TargetDeviceID: deviceID, OriginGateway: gateway.gatewayID, Event: encoded})
	return delivered
}

func (gateway *gateway) publishRoute(route routeEnvelope) {
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if err := gateway.metadata.Publish(ctx, route); err != nil {
		gateway.metrics.redisDegraded.Add(1)
		gateway.logger.Warn("redis_route_publish_failed", "error", err)
	}
}

func (gateway *gateway) handleRoute(route routeEnvelope) {
	if route.OriginGateway == gateway.gatewayID || len(route.Event) == 0 {
		return
	}
	if route.Kind == "presence" {
		gateway.realtime.routePresenceLocal(route)
		return
	}
	gateway.routeLocal(route)
}

func (gateway *gateway) routeLocal(route routeEnvelope) int {
	var connections []*connection
	if route.TargetDeviceID != "" {
		if connection := gateway.registry.forDevice(route.TargetDeviceID); connection != nil {
			connections = append(connections, connection)
		}
	} else if route.TargetUserID != "" {
		connections = gateway.registry.forUser(route.TargetUserID)
	}
	delivered := 0
	for _, connection := range connections {
		if connection.enqueue(route.Event) {
			delivered++
		} else {
			gateway.metrics.routeDrops.Add(1)
		}
	}
	return delivered
}

func (gateway *gateway) refreshMetadata(connection *connection) {
	metadata := connectionMetadata{UserID: connection.userID, DeviceID: connection.deviceID,
		SessionID: connection.sessionID, ConnectionID: connection.connectionID, GatewayID: gateway.gatewayID,
		LastSeen: time.Now().UTC()}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if err := gateway.metadata.Refresh(ctx, metadata); err != nil {
		gateway.metrics.redisDegraded.Add(1)
		gateway.logger.Debug("redis_connection_refresh_failed", "error", err)
	}
	gateway.realtime.touch(connection)
}

func (gateway *gateway) disconnect(connection *connection, code int, reason string) {
	connection.disconnectOnce.Do(func() {
		if gateway.registry.unregister(connection) {
			gateway.metrics.connections.Store(int64(gateway.registry.count()))
		}
		metadata := connectionMetadata{UserID: connection.userID, DeviceID: connection.deviceID,
			SessionID: connection.sessionID, ConnectionID: connection.connectionID, GatewayID: gateway.gatewayID,
			LastSeen: time.Now().UTC()}
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		if err := gateway.metadata.Unregister(ctx, metadata); err != nil {
			gateway.metrics.redisDegraded.Add(1)
			gateway.logger.Debug("redis_connection_unregister_failed", "error", err)
		}
		cancel()
		gateway.realtime.disconnect(connection)
		gateway.realtime.connectionOffline(connection)
	})
	connection.requestClose(code, reason)
}

func (gateway *gateway) close(ctx context.Context) error {
	var closeErr error
	gateway.closeOnce.Do(func() {
		connections := gateway.registry.all()
		for _, connection := range connections {
			gateway.disconnect(connection, websocket.CloseGoingAway, "gateway shutting down")
		}
		for _, connection := range connections {
			select {
			case <-connection.closed:
			case <-ctx.Done():
				closeErr = ctx.Err()
			}
		}
		gateway.realtime.close()
		if err := gateway.metadata.Close(); closeErr == nil {
			closeErr = err
		}
	})
	return closeErr
}

func (connection *connection) auth() authContext {
	return authContext{UserID: connection.userID, DeviceID: connection.deviceID, SessionID: connection.sessionID,
		AccessToken: connection.accessToken}
}

func writeHTTPError(writer http.ResponseWriter, status int, message string) {
	writer.Header().Set("Content-Type", "application/json")
	writer.WriteHeader(status)
	_ = json.NewEncoder(writer).Encode(map[string]string{"error": message})
}
