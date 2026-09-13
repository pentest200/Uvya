package main

import (
	"context"
	"encoding/json"
	"fmt"
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

type redisMetadataStore struct {
	client *redis.Client
	prefix string
	ttl    time.Duration
}

func newRedisMetadataStore(cfg config) *redisMetadataStore {
	return &redisMetadataStore{
		client: redis.NewClient(&redis.Options{Addr: cfg.redisAddr, Password: cfg.redisPassword, DB: cfg.redisDB}),
		prefix: cfg.redisKeyPrefix,
		ttl:    cfg.connectionMetadataTTL,
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
end
redis.call('EXPIRE', KEYS[4], ARGV[3])
return 1
`
	_, err := store.client.Eval(ctx, script, []string{store.connectionKey(metadata.ConnectionID),
		store.deviceKey(metadata.DeviceID), store.userDevicesKey(metadata.UserID),
		store.gatewayConnectionsKey(metadata.GatewayID)}, metadata.ConnectionID, metadata.LastSeen.UnixMilli(),
		int(store.ttl/time.Second)).Result()
	return err
}

func (store *redisMetadataStore) Unregister(ctx context.Context, metadata connectionMetadata) error {
	const script = `
if redis.call('HGET', KEYS[1], 'connection_id') == ARGV[1] then
  redis.call('DEL', KEYS[1])
  redis.call('SREM', KEYS[2], ARGV[2])
end
redis.call('DEL', KEYS[3])
redis.call('SREM', KEYS[4], ARGV[1])
return 1
`
	_, err := store.client.Eval(ctx, script, []string{store.deviceKey(metadata.DeviceID),
		store.userDevicesKey(metadata.UserID), store.connectionKey(metadata.ConnectionID),
		store.gatewayConnectionsKey(metadata.GatewayID)}, metadata.ConnectionID, metadata.DeviceID).Result()
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

type memoryMetadataStore struct {
	mu          sync.Mutex
	connections map[string]connectionMetadata
	subscribers []func(routeEnvelope)
}

func newMemoryMetadataStore() *memoryMetadataStore {
	return &memoryMetadataStore{connections: make(map[string]connectionMetadata)}
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
