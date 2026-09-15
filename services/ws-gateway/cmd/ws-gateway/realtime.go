package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

var errEphemeralUnavailable = errors.New("ephemeral realtime state is unavailable")

type presenceState struct {
	UserID     string    `json:"userId"`
	DeviceID   string    `json:"deviceId,omitempty"`
	Status     string    `json:"status"`
	Visibility string    `json:"visibility"`
	Activity   string    `json:"activity,omitempty"`
	LastSeen   time.Time `json:"lastSeen"`
}

type typingState struct {
	ChatID    string    `json:"chatId"`
	UserID    string    `json:"userId"`
	DeviceID  string    `json:"deviceId"`
	ExpiresAt time.Time `json:"expiresAt"`
}

type presenceSubscriber struct {
	UserID   string
	DeviceID string
}

type realtimeAuthorizer interface {
	ChatMembers(context.Context, authContext, string) (map[string]struct{}, error)
}

type allowAllRealtimeAuthorizer struct{}

func (allowAllRealtimeAuthorizer) ChatMembers(_ context.Context, _ authContext, _ string) (map[string]struct{}, error) {
	return nil, nil
}

type apiRealtimeAuthorizer struct {
	client  *http.Client
	baseURL string
}

func newAPIRealtimeAuthorizer(cfg config) *apiRealtimeAuthorizer {
	return &apiRealtimeAuthorizer{client: &http.Client{Timeout: cfg.apiTimeout}, baseURL: strings.TrimRight(cfg.apiBaseURL, "/")}
}

func (authorizer *apiRealtimeAuthorizer) ChatMembers(ctx context.Context, auth authContext, chatID string) (map[string]struct{}, error) {
	if !isUUID(chatID) {
		return nil, errors.New("chatId must be a UUID")
	}
	endpoint := authorizer.baseURL + "/v1/chats/" + url.PathEscape(chatID) + "/realtime-members"
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return nil, errors.New("unable to create realtime authorization request")
	}
	request.Header.Set("Authorization", "Bearer "+auth.AccessToken)
	request.Header.Set(requestIDHeader, newRequestID())
	response, err := authorizer.client.Do(request)
	if err != nil {
		return nil, errors.New("durable realtime authorization is unavailable")
	}
	defer response.Body.Close()
	if response.StatusCode < http.StatusOK || response.StatusCode >= http.StatusMultipleChoices {
		return nil, fmt.Errorf("realtime authorization rejected request (%d)", response.StatusCode)
	}
	var result struct{ UserIDs []string `json:"userIds"` }
	if err := json.NewDecoder(io.LimitReader(response.Body, 256*1024)).Decode(&result); err != nil {
		return nil, errors.New("realtime authorization returned invalid data")
	}
	members := make(map[string]struct{}, len(result.UserIDs))
	for _, userID := range result.UserIDs {
		if isUUID(userID) { members[userID] = struct{}{} }
	}
	return members, nil
}

type activeTyping struct {
	state     typingState
	recipients []string
	timer     *time.Timer
}

type realtimeStateManager struct {
	gateway    *gateway
	store      ephemeralStore
	authorizer realtimeAuthorizer

	mu                   sync.Mutex
	presence             map[string]presenceState
	presenceSubscriptions map[string]map[string]struct{}
	presenceTimers       map[string]*time.Timer
	presenceGenerations   map[string]uint64
	typing               map[string]activeTyping
}

func newRealtimeStateManager(gateway *gateway, store ephemeralStore) *realtimeStateManager {
	return &realtimeStateManager{gateway: gateway, store: store, authorizer: allowAllRealtimeAuthorizer{},
		presence: make(map[string]presenceState), presenceSubscriptions: make(map[string]map[string]struct{}),
		presenceTimers: make(map[string]*time.Timer), presenceGenerations: make(map[string]uint64),
		typing: make(map[string]activeTyping)}
}

func (manager *realtimeStateManager) setAuthorizer(authorizer realtimeAuthorizer) {
	if authorizer != nil { manager.authorizer = authorizer }
}

func (manager *realtimeStateManager) close() {
	manager.mu.Lock()
	defer manager.mu.Unlock()
	for userID, timer := range manager.presenceTimers {
		timer.Stop()
		delete(manager.presenceTimers, userID)
	}
	for key, activity := range manager.typing {
		if activity.timer != nil { activity.timer.Stop() }
		delete(manager.typing, key)
	}
}

func (manager *realtimeStateManager) transferSubscriptions(previous, replacement *connection) {
	manager.mu.Lock()
	defer manager.mu.Unlock()
	if targets := manager.presenceSubscriptions[previous.connectionID]; len(targets) > 0 {
		manager.presenceSubscriptions[replacement.connectionID] = targets
		delete(manager.presenceSubscriptions, previous.connectionID)
	}
}

func (manager *realtimeStateManager) connectionOnline(connection *connection) {
	if manager.store == nil { return }
	manager.mu.Lock()
	state := manager.presence[connection.userID]
	if state.UserID == "" { state = presenceState{UserID: connection.userID, Status: "online", Visibility: "online"} }
	state.UserID = connection.userID
	state.DeviceID = connection.deviceID
	state.LastSeen = time.Now().UTC()
	if state.Visibility == "" { state.Visibility = "online" }
	if state.Visibility == "invisible" { state.Status = "offline" } else { state.Status = "online" }
	manager.presence[connection.userID] = state
	manager.mu.Unlock()
	if err := manager.store.SetPresence(context.Background(), state, manager.gateway.cfg.presenceTTL); err != nil {
		manager.degraded("presence", err)
		return
	}
	manager.schedulePresence(state)
}

func (manager *realtimeStateManager) connectionOffline(connection *connection) {
	if manager.store == nil { return }
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	active, err := manager.store.ActiveUser(ctx, connection.userID)
	cancel()
	if err != nil {
		manager.degraded("presence", err)
		return
	}
	if active { return }
	state := presenceState{UserID: connection.userID, DeviceID: connection.deviceID, Status: "offline",
		Visibility: "online", LastSeen: time.Now().UTC()}
	manager.mu.Lock()
	manager.presence[connection.userID] = state
	manager.mu.Unlock()
	if err := manager.store.SetPresence(context.Background(), state, manager.gateway.cfg.presenceTTL); err != nil {
		manager.degraded("presence", err)
		return
	}
	manager.schedulePresence(state)
}

func (manager *realtimeStateManager) touch(connection *connection) {
	if manager.store == nil { return }
	now := time.Now().UTC()
	manager.mu.Lock()
	state := manager.presence[connection.userID]
	if state.UserID == "" { state = presenceState{UserID: connection.userID, Status: "online", Visibility: "online"} }
	state.UserID, state.DeviceID, state.LastSeen = connection.userID, connection.deviceID, now
	if state.Visibility == "invisible" { state.Status = "offline" } else { state.Status = "online" }
	manager.presence[connection.userID] = state
	targets := manager.subscriptionTargetsLocked(connection.connectionID)
	manager.mu.Unlock()
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if err := manager.store.SetPresence(ctx, state, manager.gateway.cfg.presenceTTL); err != nil { manager.degraded("presence", err) }
	if len(targets) > 0 {
		if err := manager.store.RefreshPresenceSubscriptions(ctx, connection.userID, connection.deviceID, targets,
			manager.gateway.cfg.presenceSubscriptionTTL); err != nil { manager.degraded("presence", err) }
	}
}

func (manager *realtimeStateManager) setPresence(connection *connection, message clientEnvelope) error {
	if manager.store == nil { return errEphemeralUnavailable }
	visibility := strings.ToLower(strings.TrimSpace(message.Visibility))
	activity := message.Activity
	if (visibility == "" || activity == "") && len(message.Payload) > 0 && string(message.Payload) != "null" {
		var payload struct {
			Visibility string `json:"visibility"`
			Activity string `json:"activity"`
		}
		if err := json.Unmarshal(message.Payload, &payload); err == nil {
			if visibility == "" { visibility = strings.ToLower(strings.TrimSpace(payload.Visibility)) }
			if activity == "" { activity = payload.Activity }
		}
	}
	if visibility == "" { visibility = "online" }
	if visibility != "online" && visibility != "invisible" { return errors.New("visibility must be online or invisible") }
	if len(activity) > 64 { return errors.New("activity is too long") }
	state := presenceState{UserID: connection.userID, DeviceID: connection.deviceID, Visibility: visibility,
		Activity: activity, LastSeen: time.Now().UTC(), Status: "online"}
	if visibility == "invisible" { state.Status = "offline" }
	if err := manager.store.SetPresence(context.Background(), state, manager.gateway.cfg.presenceTTL); err != nil {
		manager.degraded("presence", err)
		return errEphemeralUnavailable
	}
	manager.mu.Lock(); manager.presence[connection.userID] = state; manager.mu.Unlock()
	manager.gateway.metrics.presenceUpdates.Add(1)
	manager.schedulePresence(state)
	return nil
}

func (manager *realtimeStateManager) subscribe(connection *connection, message clientEnvelope) error {
	if manager.store == nil { return errEphemeralUnavailable }
	if !isUUID(message.ChatID) || len(message.UserIDs) == 0 || len(message.UserIDs) > 100 {
		return errors.New("presence.subscribe requires chatId and one to one hundred userIds")
	}
	members, err := manager.authorizer.ChatMembers(context.Background(), connection.auth(), message.ChatID)
	if err != nil { return err }
	for _, target := range message.UserIDs {
		if !isUUID(target) || (members != nil && !containsMember(members, target)) {
			return errors.New("presence subscription target is not an authorized chat member")
		}
	}
	for _, target := range message.UserIDs {
		if err := manager.store.SubscribePresence(context.Background(), target, connection.userID, connection.deviceID,
			manager.gateway.cfg.presenceSubscriptionTTL); err != nil {
			return errEphemeralUnavailable
		}
	}
	manager.mu.Lock()
	targets := manager.presenceSubscriptions[connection.connectionID]
	if targets == nil { targets = make(map[string]struct{}); manager.presenceSubscriptions[connection.connectionID] = targets }
	for _, target := range message.UserIDs { targets[target] = struct{}{} }
	manager.mu.Unlock()
	for _, target := range message.UserIDs {
		state, found, err := manager.store.GetPresence(context.Background(), target)
		if err != nil { return errEphemeralUnavailable }
		if !found { state = presenceState{UserID: target, Status: "offline", Visibility: "online"} }
		connection.enqueue(manager.presenceEvent(message.RequestID, state))
	}
	return nil
}

func (manager *realtimeStateManager) unsubscribe(connection *connection, message clientEnvelope) error {
	if manager.store == nil { return errEphemeralUnavailable }
	manager.mu.Lock()
	targets := manager.presenceSubscriptions[connection.connectionID]
	requested := message.UserIDs
	if len(requested) == 0 {
		requested = make([]string, 0, len(targets)); for target := range targets { requested = append(requested, target) }
	}
	manager.mu.Unlock()
	for _, target := range requested {
		if !isUUID(target) { return errors.New("presence subscription target is invalid") }
		if err := manager.store.UnsubscribePresence(context.Background(), target, connection.userID, connection.deviceID); err != nil {
			return errEphemeralUnavailable
		}
	}
	manager.mu.Lock()
	for _, target := range requested { delete(targets, target) }
	if len(targets) == 0 { delete(manager.presenceSubscriptions, connection.connectionID) }
	manager.mu.Unlock()
	return nil
}

func (manager *realtimeStateManager) disconnect(connection *connection) {
	if manager.store == nil { return }
	manager.mu.Lock()
	targets := manager.subscriptionTargetsLocked(connection.connectionID)
	delete(manager.presenceSubscriptions, connection.connectionID)
	var typing []activeTyping
	for key, activity := range manager.typing {
		if activity.state.UserID == connection.userID && activity.state.DeviceID == connection.deviceID {
			if activity.timer != nil { activity.timer.Stop() }
			typing = append(typing, activity)
			delete(manager.typing, key)
		}
	}
	manager.mu.Unlock()
	for target := range targets {
		if err := manager.store.UnsubscribePresence(context.Background(), target, connection.userID, connection.deviceID); err != nil {
			manager.degraded("presence", err)
		}
	}
	for _, activity := range typing {
		if err := manager.store.DeleteTyping(context.Background(), activity.state.ChatID, activity.state.UserID); err != nil {
			manager.degraded("typing", err)
			continue
		}
		manager.routeTyping(activity.state, activity.recipients, typingStop)
	}
}

func (manager *realtimeStateManager) startTyping(connection *connection, message clientEnvelope) error {
	if manager.store == nil { return errEphemeralUnavailable }
	if !isUUID(message.ChatID) { return errors.New("typing.start requires a valid chatId") }
	recipients, err := manager.typingRecipients(connection, message.ChatID)
	if err != nil { return err }
	now := time.Now().UTC()
	state := typingState{ChatID: message.ChatID, UserID: connection.userID, DeviceID: connection.deviceID,
		ExpiresAt: now.Add(manager.gateway.cfg.typingTTL)}
	if err := manager.store.SetTyping(context.Background(), state, manager.gateway.cfg.typingTTL); err != nil {
		return errEphemeralUnavailable
	}
	key := message.ChatID + ":" + connection.userID
	manager.mu.Lock()
	if previous, ok := manager.typing[key]; ok && previous.timer != nil { previous.timer.Stop() }
	activity := activeTyping{state: state, recipients: recipients}
	activity.timer = time.AfterFunc(manager.gateway.cfg.typingTTL, func() { manager.expireTyping(key, state) })
	manager.typing[key] = activity
	manager.mu.Unlock()
	manager.gateway.metrics.typingUpdates.Add(1)
	manager.routeTyping(state, recipients, typingStart)
	return nil
}

func (manager *realtimeStateManager) stopTyping(connection *connection, message clientEnvelope) error {
	if manager.store == nil { return errEphemeralUnavailable }
	if !isUUID(message.ChatID) { return errors.New("typing.stop requires a valid chatId") }
	recipients, err := manager.typingRecipients(connection, message.ChatID)
	if err != nil { return err }
	key := message.ChatID + ":" + connection.userID
	manager.mu.Lock()
	if activity, ok := manager.typing[key]; ok {
		if activity.timer != nil { activity.timer.Stop() }
		if len(activity.recipients) > 0 { recipients = activity.recipients }
		delete(manager.typing, key)
	}
	manager.mu.Unlock()
	if err := manager.store.DeleteTyping(context.Background(), message.ChatID, connection.userID); err != nil {
		return errEphemeralUnavailable
	}
	manager.routeTyping(typingState{ChatID: message.ChatID, UserID: connection.userID, DeviceID: connection.deviceID}, recipients, typingStop)
	return nil
}

func (manager *realtimeStateManager) expireTyping(key string, expected typingState) {
	manager.mu.Lock()
	activity, ok := manager.typing[key]
	if !ok || !activity.state.ExpiresAt.Equal(expected.ExpiresAt) { manager.mu.Unlock(); return }
	delete(manager.typing, key)
	manager.mu.Unlock()
	if err := manager.store.DeleteTyping(context.Background(), expected.ChatID, expected.UserID); err != nil {
		manager.degraded("typing", err); return
	}
	manager.routeTyping(expected, activity.recipients, typingStop)
}

func (manager *realtimeStateManager) typingRecipients(connection *connection, chatID string) ([]string, error) {
	members, err := manager.authorizer.ChatMembers(context.Background(), connection.auth(), chatID)
	if err != nil { return nil, err }
	if members == nil { return nil, nil }
	recipients := make([]string, 0, len(members))
	for userID := range members { if userID != connection.userID { recipients = append(recipients, userID) } }
	return recipients, nil
}

func (manager *realtimeStateManager) routeTyping(state typingState, recipients []string, eventType string) {
	expiresAt := state.ExpiresAt
	event := outboundEnvelope{Type: eventType, ChatID: state.ChatID, UserID: state.UserID, DeviceID: state.DeviceID}
	if eventType == typingStart { event.ExpiresAt = &expiresAt }
	for _, recipient := range recipients { manager.gateway.routeToUser(recipient, event) }
}

func (manager *realtimeStateManager) schedulePresence(state presenceState) {
	manager.mu.Lock()
	if timer := manager.presenceTimers[state.UserID]; timer != nil { timer.Stop() }
	manager.presenceGenerations[state.UserID]++
	generation := manager.presenceGenerations[state.UserID]
	manager.presenceTimers[state.UserID] = time.AfterFunc(manager.gateway.cfg.presenceDebounce, func() {
		manager.mu.Lock()
		if manager.presenceGenerations[state.UserID] != generation { manager.mu.Unlock(); return }
		delete(manager.presenceTimers, state.UserID)
		manager.mu.Unlock()
		manager.publishPresence(state)
	})
	manager.mu.Unlock()
}

func (manager *realtimeStateManager) publishPresence(state presenceState) {
	if manager.store == nil { return }
	subscribers, err := manager.store.PresenceSubscribers(context.Background(), state.UserID)
	if err != nil { manager.degraded("presence", err); return }
	event := manager.presenceEvent("", state)
	for _, subscriber := range subscribers {
		route := routeEnvelope{Kind: "presence", PresenceUserID: state.UserID, TargetUserID: subscriber.UserID, TargetDeviceID: subscriber.DeviceID,
			OriginGateway: manager.gateway.gatewayID, Event: event}
		manager.routePresenceLocal(route)
		manager.gateway.publishRoute(route)
	}
}

func (manager *realtimeStateManager) presenceEvent(requestID string, state presenceState) []byte {
	var lastSeen *time.Time
	if !state.LastSeen.IsZero() {
		value := state.LastSeen
		lastSeen = &value
	}
	return marshalEvent(outboundEnvelope{Type: presenceUpdated, RequestID: requestID, UserID: state.UserID,
		DeviceID: state.DeviceID, Status: state.Status, Visibility: state.Visibility, Activity: state.Activity, LastSeen: lastSeen})
}

func (manager *realtimeStateManager) routePresenceLocal(route routeEnvelope) int {
	connection := manager.gateway.registry.forDevice(route.TargetDeviceID)
	if connection == nil || !manager.hasSubscription(connection.connectionID, route.PresenceUserID) { return 0 }
	if connection.enqueue(route.Event) { return 1 }
	manager.gateway.metrics.routeDrops.Add(1)
	return 0
}

func (manager *realtimeStateManager) hasSubscription(connectionID, targetUserID string) bool {
	manager.mu.Lock(); defer manager.mu.Unlock()
	_, ok := manager.presenceSubscriptions[connectionID][targetUserID]
	return ok
}

func (manager *realtimeStateManager) subscriptionTargetsLocked(connectionID string) map[string]struct{} {
	result := make(map[string]struct{})
	for target := range manager.presenceSubscriptions[connectionID] { result[target] = struct{}{} }
	return result
}

func containsMember(members map[string]struct{}, userID string) bool { _, ok := members[userID]; return ok }

func (manager *realtimeStateManager) degraded(kind string, err error) {
	if kind == "typing" { manager.gateway.metrics.typingDegraded.Add(1) } else { manager.gateway.metrics.presenceDegraded.Add(1) }
	manager.gateway.logger.Debug("realtime_state_degraded", "kind", kind, "error", err)
}
