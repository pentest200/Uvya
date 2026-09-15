package main

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"sync"
	"time"

	"github.com/redis/go-redis/v9"
)

type connectionMetadata struct {
	UserID       string
	DeviceID     string
	SessionID    string
	ConnectionID string
	GatewayID    string
	LastSeen     time.Time
}

type routeEnvelope struct {
	Kind             string          `json:"kind,omitempty"`
	PresenceUserID   string          `json:"presenceUserId,omitempty"`
	TargetUserID   string          `json:"targetUserId,omitempty"`
	TargetDeviceID string          `json:"targetDeviceId,omitempty"`
	OriginGateway  string          `json:"originGateway"`
	Event          json.RawMessage `json:"event"`
}

type metadataStore interface {
	Ready(context.Context) error
	Register(context.Context, connectionMetadata) error
	Refresh(context.Context, connectionMetadata) error
	Unregister(context.Context, connectionMetadata) error
	Publish(context.Context, routeEnvelope) error
	Subscribe(context.Context, func(routeEnvelope)) error
	Close() error
}

type ephemeralStore interface {
	SetPresence(context.Context, presenceState, time.Duration) error
	GetPresence(context.Context, string) (presenceState, bool, error)
	DeletePresence(context.Context, string) error
	SetTyping(context.Context, typingState, time.Duration) error
	GetTyping(context.Context, string, string) (typingState, bool, error)
	DeleteTyping(context.Context, string, string) error
	SubscribePresence(context.Context, string, string, string, time.Duration) error
	UnsubscribePresence(context.Context, string, string, string) error
	RefreshPresenceSubscriptions(context.Context, string, string, []string, time.Duration) error
	PresenceSubscribers(context.Context, string) ([]presenceSubscriber, error)
	ActiveUser(context.Context, string) (bool, error)
}

type redisMetadataStore struct {
	client *redis.Client
	prefix string
	ttl    time.Duration
	presenceTTL time.Duration
	typingTTL time.Duration
	presenceSubscriptionTTL time.Duration
}

func newRedisMetadataStore(cfg config) *redisMetadataStore {
	return &redisMetadataStore{
		client: redis.NewClient(&redis.Options{Addr: cfg.redisAddr, Password: cfg.redisPassword, DB: cfg.redisDB}),
		prefix: cfg.redisKeyPrefix,
		ttl:    cfg.connectionMetadataTTL,
		presenceTTL: cfg.presenceTTL,
		typingTTL: cfg.typingTTL,
		presenceSubscriptionTTL: cfg.presenceSubscriptionTTL,
	}
}

func (store *redisMetadataStore) Ready(ctx context.Context) error {
	return store.client.Ping(ctx).Err()
}

func (store *redisMetadataStore) Register(ctx context.Context, metadata connectionMetadata) error {
	now := metadata.LastSeen.UnixMilli()
	pipe := store.client.TxPipeline()
	pipe.HSet(ctx, store.connectionKey(metadata.ConnectionID), map[string]any{
		"user_id": metadata.UserID, "device_id": metadata.DeviceID, "session_id": metadata.SessionID,
		"gateway_id": metadata.GatewayID, "last_seen": now,
	})
	pipe.Expire(ctx, store.connectionKey(metadata.ConnectionID), store.ttl)
	pipe.HSet(ctx, store.deviceKey(metadata.DeviceID), map[string]any{
		"connection_id": metadata.ConnectionID, "user_id": metadata.UserID, "gateway_id": metadata.GatewayID,
		"last_seen": now,
	})
	pipe.Expire(ctx, store.deviceKey(metadata.DeviceID), store.ttl)
	pipe.Set(ctx, store.deviceConnectionKey(metadata.DeviceID), metadata.ConnectionID, store.ttl)
	pipe.SAdd(ctx, store.userDevicesKey(metadata.UserID), metadata.DeviceID)
	pipe.Expire(ctx, store.userDevicesKey(metadata.UserID), store.ttl)
	pipe.SAdd(ctx, store.gatewayConnectionsKey(metadata.GatewayID), metadata.ConnectionID)
	pipe.Expire(ctx, store.gatewayConnectionsKey(metadata.GatewayID), store.ttl)
	_, err := pipe.Exec(ctx)
	return err
}

func (store *redisMetadataStore) Refresh(ctx context.Context, metadata connectionMetadata) error {
	const script = `
redis.call('HSET', KEYS[1], 'last_seen', ARGV[2])
redis.call('EXPIRE', KEYS[1], ARGV[3])
if redis.call('HGET', KEYS[2], 'connection_id') == ARGV[1] then
  redis.call('HSET', KEYS[2], 'last_seen', ARGV[2])
  redis.call('EXPIRE', KEYS[2], ARGV[3])
  redis.call('EXPIRE', KEYS[3], ARGV[3])
  redis.call('SET', KEYS[5], ARGV[1], 'EX', ARGV[3])
end
redis.call('EXPIRE', KEYS[4], ARGV[3])
return 1
`
	_, err := store.client.Eval(ctx, script, []string{store.connectionKey(metadata.ConnectionID),
		store.deviceKey(metadata.DeviceID), store.userDevicesKey(metadata.UserID),
		store.gatewayConnectionsKey(metadata.GatewayID), store.deviceConnectionKey(metadata.DeviceID)}, metadata.ConnectionID, metadata.LastSeen.UnixMilli(),
		int(store.ttl/time.Second)).Result()
	return err
}

func (store *redisMetadataStore) Unregister(ctx context.Context, metadata connectionMetadata) error {
	const script = `
if redis.call('HGET', KEYS[1], 'connection_id') == ARGV[1] then
  redis.call('DEL', KEYS[1])
	redis.call('DEL', KEYS[5])
  redis.call('SREM', KEYS[2], ARGV[2])
end
redis.call('DEL', KEYS[3])
redis.call('SREM', KEYS[4], ARGV[1])
return 1
`
	_, err := store.client.Eval(ctx, script, []string{store.deviceKey(metadata.DeviceID),
		store.userDevicesKey(metadata.UserID), store.connectionKey(metadata.ConnectionID),
		store.gatewayConnectionsKey(metadata.GatewayID), store.deviceConnectionKey(metadata.DeviceID)}, metadata.ConnectionID, metadata.DeviceID).Result()
	return err
}

func (store *redisMetadataStore) Publish(ctx context.Context, route routeEnvelope) error {
	encoded, err := json.Marshal(route)
	if err != nil {
		return err
	}
	return store.client.Publish(ctx, store.routeChannel(), encoded).Err()
}

func (store *redisMetadataStore) Subscribe(ctx context.Context, handler func(routeEnvelope)) error {
	pubsub := store.client.Subscribe(ctx, store.routeChannel())
	go func() {
		defer pubsub.Close()
		if _, err := pubsub.Receive(ctx); err != nil {
			return
		}
		for message := range pubsub.Channel() {
			var route routeEnvelope
			if json.Unmarshal([]byte(message.Payload), &route) == nil {
				handler(route)
			}
		}
	}()
	return nil
}

func (store *redisMetadataStore) Close() error { return store.client.Close() }

func (store *redisMetadataStore) connectionKey(connectionID string) string {
	return fmt.Sprintf("%s:connection:%s", store.prefix, connectionID)
}

func (store *redisMetadataStore) userDevicesKey(userID string) string {
	return fmt.Sprintf("%s:user:%s:devices", store.prefix, userID)
}

func (store *redisMetadataStore) deviceKey(deviceID string) string {
	return fmt.Sprintf("%s:device:%s", store.prefix, deviceID)
}

func (store *redisMetadataStore) gatewayConnectionsKey(gatewayID string) string {
	return fmt.Sprintf("%s:gateway:%s:connections", store.prefix, gatewayID)
}

func (store *redisMetadataStore) routeChannel() string { return store.prefix + ":routes" }

func (store *redisMetadataStore) SetPresence(ctx context.Context, state presenceState, ttl time.Duration) error {
	encoded, err := json.Marshal(state)
	if err != nil { return err }
	return store.client.Set(ctx, store.presenceKey(state.UserID), encoded, ttl).Err()
}

func (store *redisMetadataStore) GetPresence(ctx context.Context, userID string) (presenceState, bool, error) {
	value, err := store.client.Get(ctx, store.presenceKey(userID)).Bytes()
	if err == redis.Nil { return presenceState{}, false, nil }
	if err != nil { return presenceState{}, false, err }
	var state presenceState
	if err := json.Unmarshal(value, &state); err != nil { return presenceState{}, false, err }
	return state, true, nil
}

func (store *redisMetadataStore) DeletePresence(ctx context.Context, userID string) error {
	return store.client.Del(ctx, store.presenceKey(userID)).Err()
}

func (store *redisMetadataStore) SetTyping(ctx context.Context, state typingState, ttl time.Duration) error {
	encoded, err := json.Marshal(state)
	if err != nil { return err }
	return store.client.Set(ctx, store.typingKey(state.ChatID, state.UserID), encoded, ttl).Err()
}

func (store *redisMetadataStore) GetTyping(ctx context.Context, chatID, userID string) (typingState, bool, error) {
	value, err := store.client.Get(ctx, store.typingKey(chatID, userID)).Bytes()
	if err == redis.Nil { return typingState{}, false, nil }
	if err != nil { return typingState{}, false, err }
	var state typingState
	if err := json.Unmarshal(value, &state); err != nil { return typingState{}, false, err }
	return state, true, nil
}

func (store *redisMetadataStore) DeleteTyping(ctx context.Context, chatID, userID string) error {
	return store.client.Del(ctx, store.typingKey(chatID, userID)).Err()
}

func (store *redisMetadataStore) SubscribePresence(ctx context.Context, targetUserID, subscriberUserID, subscriberDeviceID string, ttl time.Duration) error {
	key := store.presenceWatchersKey(targetUserID)
	member := subscriberUserID + "|" + subscriberDeviceID
	pipe := store.client.TxPipeline()
	pipe.SAdd(ctx, key, member)
	pipe.Expire(ctx, key, ttl)
	_, err := pipe.Exec(ctx)
	return err
}

func (store *redisMetadataStore) UnsubscribePresence(ctx context.Context, targetUserID, subscriberUserID, subscriberDeviceID string) error {
	return store.client.SRem(ctx, store.presenceWatchersKey(targetUserID), subscriberUserID+"|"+subscriberDeviceID).Err()
}

func (store *redisMetadataStore) RefreshPresenceSubscriptions(ctx context.Context, subscriberUserID, subscriberDeviceID string, targets []string, ttl time.Duration) error {
	pipe := store.client.TxPipeline()
	member := subscriberUserID + "|" + subscriberDeviceID
	for _, target := range targets {
		key := store.presenceWatchersKey(target)
		pipe.SAdd(ctx, key, member)
		pipe.Expire(ctx, key, ttl)
	}
	_, err := pipe.Exec(ctx)
	return err
}

func (store *redisMetadataStore) PresenceSubscribers(ctx context.Context, targetUserID string) ([]presenceSubscriber, error) {
	values, err := store.client.SMembers(ctx, store.presenceWatchersKey(targetUserID)).Result()
	if err != nil { return nil, err }
	result := make([]presenceSubscriber, 0, len(values))
	for _, value := range values {
		parts := strings.Split(value, "|")
		if len(parts) == 2 && isUUID(parts[0]) && isUUID(parts[1]) {
			result = append(result, presenceSubscriber{UserID: parts[0], DeviceID: parts[1]})
		}
	}
	return result, nil
}

func (store *redisMetadataStore) ActiveUser(ctx context.Context, userID string) (bool, error) {
	count, err := store.client.SCard(ctx, store.userDevicesKey(userID)).Result()
	return count > 0, err
}

func (store *redisMetadataStore) presenceKey(userID string) string { return fmt.Sprintf("%s:presence:%s", store.prefix, userID) }
func (store *redisMetadataStore) typingKey(chatID, userID string) string { return fmt.Sprintf("%s:typing:%s:%s", store.prefix, chatID, userID) }
func (store *redisMetadataStore) presenceWatchersKey(userID string) string { return fmt.Sprintf("%s:presence:watchers:%s", store.prefix, userID) }
func (store *redisMetadataStore) deviceConnectionKey(deviceID string) string { return fmt.Sprintf("%s:device:%s:connection", store.prefix, deviceID) }

type memoryMetadataStore struct {
	mu          sync.Mutex
	connections map[string]connectionMetadata
	subscribers []func(routeEnvelope)
	presence    map[string]memoryPresence
	typing      map[string]memoryTyping
	watchers    map[string]map[string]presenceSubscriber
}

type memoryPresence struct { state presenceState; expiresAt time.Time }
type memoryTyping struct { state typingState; expiresAt time.Time }

func newMemoryMetadataStore() *memoryMetadataStore {
	return &memoryMetadataStore{connections: make(map[string]connectionMetadata), presence: make(map[string]memoryPresence),
		typing: make(map[string]memoryTyping), watchers: make(map[string]map[string]presenceSubscriber)}
}

func (store *memoryMetadataStore) Ready(context.Context) error { return nil }

func (store *memoryMetadataStore) Register(_ context.Context, metadata connectionMetadata) error {
	store.mu.Lock()
	defer store.mu.Unlock()
	store.connections[metadata.ConnectionID] = metadata
	return nil
}

func (store *memoryMetadataStore) Refresh(_ context.Context, metadata connectionMetadata) error {
	store.mu.Lock()
	defer store.mu.Unlock()
	if _, ok := store.connections[metadata.ConnectionID]; ok {
		store.connections[metadata.ConnectionID] = metadata
	}
	return nil
}

func (store *memoryMetadataStore) Unregister(_ context.Context, metadata connectionMetadata) error {
	store.mu.Lock()
	defer store.mu.Unlock()
	delete(store.connections, metadata.ConnectionID)
	return nil
}

func (store *memoryMetadataStore) Publish(_ context.Context, route routeEnvelope) error {
	store.mu.Lock()
	subscribers := append([]func(routeEnvelope){}, store.subscribers...)
	store.mu.Unlock()
	for _, subscriber := range subscribers {
		subscriber(route)
	}
	return nil
}

func (store *memoryMetadataStore) Subscribe(_ context.Context, handler func(routeEnvelope)) error {
	store.mu.Lock()
	defer store.mu.Unlock()
	store.subscribers = append(store.subscribers, handler)
	return nil
}

func (store *memoryMetadataStore) Close() error { return nil }

func (store *memoryMetadataStore) SetPresence(_ context.Context, state presenceState, ttl time.Duration) error {
	store.mu.Lock(); defer store.mu.Unlock()
	store.presence[state.UserID] = memoryPresence{state: state, expiresAt: time.Now().Add(ttl)}
	return nil
}

func (store *memoryMetadataStore) GetPresence(_ context.Context, userID string) (presenceState, bool, error) {
	store.mu.Lock(); defer store.mu.Unlock()
	value, ok := store.presence[userID]
	if !ok { return presenceState{}, false, nil }
	if time.Now().After(value.expiresAt) { delete(store.presence, userID); return presenceState{}, false, nil }
	return value.state, true, nil
}

func (store *memoryMetadataStore) DeletePresence(_ context.Context, userID string) error {
	store.mu.Lock(); defer store.mu.Unlock(); delete(store.presence, userID); return nil
}

func (store *memoryMetadataStore) SetTyping(_ context.Context, state typingState, ttl time.Duration) error {
	store.mu.Lock(); defer store.mu.Unlock()
	store.typing[state.ChatID+":"+state.UserID] = memoryTyping{state: state, expiresAt: time.Now().Add(ttl)}
	return nil
}

func (store *memoryMetadataStore) GetTyping(_ context.Context, chatID, userID string) (typingState, bool, error) {
	store.mu.Lock(); defer store.mu.Unlock()
	key := chatID + ":" + userID
	value, ok := store.typing[key]
	if !ok { return typingState{}, false, nil }
	if time.Now().After(value.expiresAt) { delete(store.typing, key); return typingState{}, false, nil }
	return value.state, true, nil
}

func (store *memoryMetadataStore) DeleteTyping(_ context.Context, chatID, userID string) error {
	store.mu.Lock(); defer store.mu.Unlock(); delete(store.typing, chatID+":"+userID); return nil
}

func (store *memoryMetadataStore) SubscribePresence(_ context.Context, targetUserID, subscriberUserID, subscriberDeviceID string, _ time.Duration) error {
	store.mu.Lock(); defer store.mu.Unlock()
	if store.watchers[targetUserID] == nil { store.watchers[targetUserID] = make(map[string]presenceSubscriber) }
	key := subscriberUserID + "|" + subscriberDeviceID
	store.watchers[targetUserID][key] = presenceSubscriber{UserID: subscriberUserID, DeviceID: subscriberDeviceID}
	return nil
}

func (store *memoryMetadataStore) UnsubscribePresence(_ context.Context, targetUserID, subscriberUserID, subscriberDeviceID string) error {
	store.mu.Lock(); defer store.mu.Unlock()
	delete(store.watchers[targetUserID], subscriberUserID+"|"+subscriberDeviceID)
	return nil
}

func (store *memoryMetadataStore) RefreshPresenceSubscriptions(_ context.Context, subscriberUserID, subscriberDeviceID string, targets []string, _ time.Duration) error {
	for _, target := range targets { if err := store.SubscribePresence(context.Background(), target, subscriberUserID, subscriberDeviceID, 0); err != nil { return err } }
	return nil
}

func (store *memoryMetadataStore) PresenceSubscribers(_ context.Context, targetUserID string) ([]presenceSubscriber, error) {
	store.mu.Lock(); defer store.mu.Unlock()
	values := store.watchers[targetUserID]
	result := make([]presenceSubscriber, 0, len(values))
	for _, value := range values { result = append(result, value) }
	return result, nil
}

func (store *memoryMetadataStore) ActiveUser(_ context.Context, userID string) (bool, error) {
	store.mu.Lock(); defer store.mu.Unlock()
	for _, value := range store.connections { if value.UserID == userID { return true, nil } }
	return false, nil
}
