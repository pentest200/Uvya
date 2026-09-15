package main

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"testing"
	"time"
)

func TestEphemeralStateExpiresFromRedisCompatibleTTLStore(t *testing.T) {
	store := newMemoryMetadataStore()
	userID := "11111111-1111-4111-8111-111111111111"
	chatID := "22222222-2222-4222-8222-222222222222"
	now := time.Now().UTC()
	if err := store.SetPresence(context.Background(), presenceState{UserID: userID, Status: "online", LastSeen: now}, 20*time.Millisecond); err != nil {
		t.Fatal(err)
	}
	if err := store.SetTyping(context.Background(), typingState{ChatID: chatID, UserID: userID, ExpiresAt: now.Add(20 * time.Millisecond)}, 20*time.Millisecond); err != nil {
		t.Fatal(err)
	}
	if _, found, _ := store.GetPresence(context.Background(), userID); !found {
		t.Fatal("presence should be available before TTL expiry")
	}
	if _, found, _ := store.GetTyping(context.Background(), chatID, userID); !found {
		t.Fatal("typing should be available before TTL expiry")
	}
	waitFor(t, func() bool {
		_, presenceFound, _ := store.GetPresence(context.Background(), userID)
		_, typingFound, _ := store.GetTyping(context.Background(), chatID, userID)
		return !presenceFound && !typingFound
	})
}

func TestPresenceRoutesOnlyToSubscribedDeviceAndDebounces(t *testing.T) {
	metadata := newMemoryMetadataStore()
	cfg := loadConfig()
	cfg.presenceDebounce = 15 * time.Millisecond
	gateway := newGatewayWithDependencies(cfg, slog.New(slog.NewTextHandler(io.Discard, nil)), "gateway-test",
		metadata, &fakeMessageRouter{}, &fakeSyncProvider{}, noopCommandRouter{}, nil)
	metadata.Subscribe(context.Background(), gateway.handleRoute)
	targetID := "11111111-1111-4111-8111-111111111111"
	subscriberID := "22222222-2222-4222-8222-222222222222"
	deviceID := "33333333-3333-4333-8333-333333333333"
	chatID := "44444444-4444-4444-8444-444444444444"
	subscriber := &connection{gateway: gateway, userID: subscriberID, deviceID: deviceID, connectionID: "connection-1",
		outbound: make(chan []byte, 8), done: make(chan struct{})}
	gateway.registry.register(subscriber)
	if err := gateway.realtime.subscribe(subscriber, clientEnvelope{ChatID: chatID, UserIDs: []string{targetID}, RequestID: "subscribe-1"}); err != nil {
		t.Fatal(err)
	}
	// The subscribe response is the current offline snapshot.
	select {
	case <-subscriber.outbound:
	case <-time.After(time.Second):
		t.Fatal("expected initial presence snapshot")
	}
	state := presenceState{UserID: targetID, DeviceID: deviceID, Status: "online", Visibility: "online", LastSeen: time.Now().UTC()}
	if err := metadata.SetPresence(context.Background(), state, time.Minute); err != nil {
		t.Fatal(err)
	}
	gateway.realtime.schedulePresence(state)
	gateway.realtime.schedulePresence(presenceState{UserID: targetID, Status: "online", Visibility: "online", LastSeen: time.Now().UTC()})
	var event outboundEnvelope
	select {
	case payload := <-subscriber.outbound:
		if err := json.Unmarshal(payload, &event); err != nil { t.Fatal(err) }
	case <-time.After(time.Second):
		t.Fatal("expected debounced presence event")
	}
	if event.Type != presenceUpdated || event.UserID != targetID { t.Fatalf("unexpected presence event: %+v", event) }
	select {
	case <-subscriber.outbound:
		t.Fatal("expected duplicate state changes to be debounced")
	case <-time.After(50 * time.Millisecond):
	}

	nonSubscriber := &connection{gateway: gateway, userID: "55555555-5555-4555-8555-555555555555", deviceID: "66666666-6666-4666-8666-666666666666",
		connectionID: "connection-2", outbound: make(chan []byte, 1), done: make(chan struct{})}
	gateway.registry.register(nonSubscriber)
	if delivered := gateway.realtime.routePresenceLocal(routeEnvelope{Kind: "presence", PresenceUserID: targetID,
		TargetDeviceID: nonSubscriber.deviceID, Event: []byte(`{"type":"presence.updated"}`)}); delivered != 0 {
		t.Fatal("presence was routed to an unsubscribed device")
	}
}

func TestReconnectKeepsPresenceOnlineUntilLastDeviceDisconnects(t *testing.T) {
	userID := "11111111-1111-4111-8111-111111111111"
	deviceID := "33333333-3333-4333-8333-333333333333"
	gateway, server, token := testGateway(t, &fakeMessageRouter{}, &fakeSyncProvider{}, userID, deviceID)
	defer server.Close()
	store := gateway.metadata.(*memoryMetadataStore)
	first, _ := dialTestConnection(t, server, token, deviceID)
	waitFor(t, func() bool {
		state, found, _ := store.GetPresence(context.Background(), userID)
		return found && state.Status == "online"
	})
	reconnected, _ := dialTestConnection(t, server, token, deviceID)
	defer reconnected.Close()
	waitFor(t, func() bool { return gateway.registry.count() == 1 })
	state, found, _ := store.GetPresence(context.Background(), userID)
	if !found || state.Status != "online" { t.Fatalf("expected online presence after reconnect: %+v found=%v", state, found) }
	_ = first.Close()
	_ = reconnected.Close()
	waitFor(t, func() bool { return gateway.registry.count() == 0 })
	waitFor(t, func() bool {
		state, found, _ := store.GetPresence(context.Background(), userID)
		return found && state.Status == "offline"
	})
}

func TestMessagePersistenceSurvivesEphemeralStateFailure(t *testing.T) {
	messageID := "55555555-5555-4555-8555-555555555555"
	chatID := "22222222-2222-4222-8222-222222222222"
	router := &fakeMessageRouter{result: persistedMessage{MessageID: messageID, ChatID: chatID, Sequence: 7}}
	gateway := newGatewayWithDependencies(loadConfig(), slog.New(slog.NewTextHandler(io.Discard, nil)), "gateway-test",
		newMemoryMetadataStore(), router, &fakeSyncProvider{}, noopCommandRouter{}, nil)
	gateway.realtime.store = nil
	connection := &connection{gateway: gateway, userID: "11111111-1111-4111-8111-111111111111",
		deviceID: "33333333-3333-4333-8333-333333333333", outbound: make(chan []byte, 1), done: make(chan struct{})}
	gateway.handleMessageSend(connection, clientEnvelope{Type: messageSend, RequestID: "send-1", ChatID: chatID,
		ClientMessageID: "66666666-6666-4666-8666-666666666666", Payload: json.RawMessage(`{"type":"text","body":"hello"}`)})
	var response outboundEnvelope
	select {
	case payload := <-connection.outbound:
		if err := json.Unmarshal(payload, &response); err != nil { t.Fatal(err) }
	case <-time.After(time.Second):
		t.Fatal("message persistence did not return while realtime state was unavailable")
	}
	if response.Type != messagePersisted || router.called != 1 { t.Fatalf("unexpected durable message result: %+v calls=%d", response, router.called) }
}
