package main

import (
	"context"
	"crypto/rand"
	"crypto/rsa"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/gorilla/websocket"
)

func TestMultipleDevicesReceiveRoutedEventsAndReconnect(t *testing.T) {
	userID := "11111111-1111-4111-8111-111111111111"
	chatID := "22222222-2222-4222-8222-222222222222"
	deviceOne := "33333333-3333-4333-8333-333333333333"
	deviceTwo := "44444444-4444-4444-8444-444444444444"
	messageID := "55555555-5555-4555-8555-555555555555"

	router := &fakeMessageRouter{result: persistedMessage{MessageID: messageID, ChatID: chatID, Sequence: 123}}
	syncer := &fakeSyncProvider{delta: outboundEnvelope{Type: syncDelta, Messages: []json.RawMessage{
		json.RawMessage(`{"messageId":"55555555-5555-4555-8555-555555555555"}`),
	}}}
	gateway, server, token := testGateway(t, router, syncer, userID, deviceOne)
	defer server.Close()

	first, _ := dialTestConnection(t, server, token, deviceOne)
	second, _ := dialTestConnection(t, server, tokenFor(t, gateway.validator, userID, deviceTwo), deviceTwo)
	defer first.Close()
	defer second.Close()
	waitFor(t, func() bool { return gateway.registry.count() == 2 })

	if err := first.WriteJSON(map[string]any{"type": messageSend, "requestId": "send-1", "chatId": chatID,
		"clientMessageId": "66666666-6666-4666-8666-666666666666", "payload": map[string]any{
			"type": "text", "body": "hello",
		}}); err != nil {
		t.Fatal(err)
	}
	var persisted outboundEnvelope
	readJSON(t, first, &persisted)
	if persisted.Type != messagePersisted || persisted.MessageID != messageID || persisted.Sequence != 123 {
		t.Fatalf("unexpected persisted response: %+v", persisted)
	}

	eventPayload := json.RawMessage(`{"body":"hello"}`)
	gateway.routeToUser(userID, outboundEnvelope{Type: messageNew, MessageID: messageID, ChatID: chatID,
		Sequence: 123, Payload: eventPayload})
	var firstEvent, secondEvent outboundEnvelope
	readJSON(t, first, &firstEvent)
	readJSON(t, second, &secondEvent)
	if firstEvent.Type != messageNew || secondEvent.Type != messageNew {
		t.Fatalf("expected both devices to receive message.new: %+v %+v", firstEvent, secondEvent)
	}

	resume := map[string]any{"type": syncResume, "requestId": "resume-1", "deviceId": deviceOne,
		"globalSyncCursor": "global-7", "perChatCursors": map[string]string{chatID: "122"},
		"lastAcknowledgedClientMessageId": "66666666-6666-4666-8666-666666666666"}
	if err := first.WriteJSON(resume); err != nil {
		t.Fatal(err)
	}
	var delta outboundEnvelope
	readJSON(t, first, &delta)
	if delta.Type != syncDelta || delta.RequestID != "resume-1" || len(delta.Messages) != 1 {
		t.Fatalf("unexpected sync delta: %+v", delta)
	}

	newFirst, _ := dialTestConnection(t, server, token, deviceOne)
	defer newFirst.Close()
	waitFor(t, func() bool { return gateway.registry.count() == 2 && gateway.metrics.reconnects.Load() == 1 })
	delivered := gateway.routeToDevice(deviceOne, outboundEnvelope{Type: presenceUpdated,
		Payload: json.RawMessage(`{"status":"online"}`)})
	if delivered != 1 {
		t.Fatalf("expected one routed event, got %d", delivered)
	}
	var presence outboundEnvelope
	readJSON(t, newFirst, &presence)
	if presence.Type != presenceUpdated {
		t.Fatalf("expected reconnecting device to receive routed event: %+v", presence)
	}
}

func TestHandshakeRequiresValidAccessToken(t *testing.T) {
	userID := "11111111-1111-4111-8111-111111111111"
	deviceID := "33333333-3333-4333-8333-333333333333"
	_, server, _ := testGateway(t, &fakeMessageRouter{}, &fakeSyncProvider{}, userID, deviceID)
	defer server.Close()
	endpoint := "ws" + strings.TrimPrefix(server, "http") + "/ws?deviceId=" + deviceID
	_, response, err := websocket.DefaultDialer.Dial(endpoint, nil)
	if err == nil {
		t.Fatal("expected unauthenticated handshake to be rejected")
	}
	if response == nil || response.StatusCode != http.StatusUnauthorized {
		t.Fatalf("expected HTTP 401, got response=%v error=%v", response, err)
	}
}

func TestHeartbeatAckAndBackpressureClose(t *testing.T) {
	userID := "11111111-1111-4111-8111-111111111111"
	deviceID := "33333333-3333-4333-8333-333333333333"
	gateway, server, token := testGateway(t, &fakeMessageRouter{}, &fakeSyncProvider{}, userID, deviceID)
	defer server.Close()
	socket, _ := dialTestConnection(t, server, token, deviceID)
	defer socket.Close()
	if err := socket.WriteJSON(map[string]any{"type": heartbeat, "requestId": "hb-1"}); err != nil {
		t.Fatal(err)
	}
	var ack outboundEnvelope
	readJSON(t, socket, &ack)
	if ack.Type != heartbeatAck || ack.RequestID != "hb-1" {
		t.Fatalf("unexpected heartbeat ack: %+v", ack)
	}

	limited := &connection{gateway: gateway, outbound: make(chan []byte, 1), done: make(chan struct{}),
		closed: make(chan struct{}), connectionID: "not-registered", userID: userID, deviceID: deviceID}
	if !limited.enqueue([]byte("one")) || limited.enqueue([]byte("two")) {
		t.Fatal("expected the full queue to apply backpressure")
	}
	if gateway.metrics.backpressure.Load() != 1 {
		t.Fatalf("expected one backpressure event, got %d", gateway.metrics.backpressure.Load())
	}
}

type fakeMessageRouter struct {
	mu     sync.Mutex
	result persistedMessage
	called int
}

func (router *fakeMessageRouter) Persist(_ context.Context, _ authContext, _ clientEnvelope) (persistedMessage, error) {
	router.mu.Lock()
	defer router.mu.Unlock()
	router.called++
	if router.result.MessageID == "" {
		return persistedMessage{}, io.ErrClosedPipe
	}
	return router.result, nil
}

type fakeSyncProvider struct {
	delta outboundEnvelope
}

func (provider *fakeSyncProvider) Resume(_ context.Context, _ authContext, request syncRequest) (outboundEnvelope, error) {
	delta := provider.delta
	delta.RequestID = request.RequestID
	delta.GlobalSyncCursor = request.GlobalSyncCursor
	delta.PerChatCursors = cloneCursors(request.PerChatCursors)
	delta.LastAcknowledgedClientMessage = request.LastAcknowledgedClientMessage
	return delta, nil
}

func testGateway(t *testing.T, router messageRouter, syncer syncProvider, userID, deviceID string) (*gateway,
	*httptest.Server, string) {
	t.Helper()
	privateKey, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	cfg := loadConfig()
	cfg.heartbeatInterval = 100 * time.Millisecond
	cfg.heartbeatTimeout = time.Second
	cfg.writeTimeout = time.Second
	cfg.sendQueueSize = 16
	validator := &accessTokenValidator{publicKey: &privateKey.PublicKey, issuer: "test-issuer", staticKey: true,
		client: &http.Client{Timeout: time.Second}}
	testSigningKeys.Store(validator, privateKey)
	gateway := newGatewayWithDependencies(cfg, slog.New(slog.NewTextHandler(io.Discard, nil)), "gateway-test",
		newMemoryMetadataStore(), router, syncer, noopCommandRouter{}, validator)
	server := httptest.NewServer(gateway.handler())
	t.Cleanup(func() {
		_ = gateway.close(context.Background())
		testSigningKeys.Delete(validator)
	})
	return gateway, server, tokenFor(t, validator, userID, deviceID)
}

func tokenFor(t *testing.T, validator *accessTokenValidator, userID, deviceID string) string {
	t.Helper()
	return signedTestToken(t, validator, userID, deviceID)
}

func signedTestToken(t *testing.T, validator *accessTokenValidator, userID, deviceID string) string {
	t.Helper()
	return signWithTestKey(t, validator, userID, deviceID)
}

var testSigningKeys sync.Map

func signWithTestKey(t *testing.T, validator *accessTokenValidator, userID, deviceID string) string {
	t.Helper()
	keyValue, ok := testSigningKeys.Load(validator)
	if !ok {
		t.Fatal("test signing key was not registered")
	}
	privateKey := keyValue.(*rsa.PrivateKey)
	now := time.Now()
	claims := jwt.RegisteredClaims{Issuer: validator.issuer, Subject: userID, ExpiresAt: jwt.NewNumericDate(now.Add(time.Hour)),
		IssuedAt: jwt.NewNumericDate(now)}
	claimsMap := jwt.MapClaims{"iss": claims.Issuer, "sub": claims.Subject, "exp": claims.ExpiresAt.Unix(),
		"iat": claims.IssuedAt.Unix(), "sid": "77777777-7777-4777-8777-777777777777", "did": deviceID, "scope": "user"}
	token := jwt.NewWithClaims(jwt.SigningMethodRS256, claimsMap)
	signed, err := token.SignedString(privateKey)
	if err != nil {
		t.Fatal(err)
	}
	return signed
}

func dialTestConnection(t *testing.T, server, token, deviceID string) (*websocket.Conn, *http.Response) {
	t.Helper()
	endpoint := "ws" + strings.TrimPrefix(server, "http") + "/ws?access_token=" + url.QueryEscape(token) + "&deviceId=" + deviceID
	connection, response, err := websocket.DefaultDialer.Dial(endpoint, nil)
	if err != nil {
		t.Fatalf("dial websocket: %v", err)
	}
	return connection, response
}

func readJSON(t *testing.T, connection *websocket.Conn, target any) {
	t.Helper()
	_ = connection.SetReadDeadline(time.Now().Add(2 * time.Second))
	if err := connection.ReadJSON(target); err != nil {
		t.Fatal(err)
	}
}

func waitFor(t *testing.T, predicate func() bool) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if predicate() {
			return
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatal("condition was not reached")
}
