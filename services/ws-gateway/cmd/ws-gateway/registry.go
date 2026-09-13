package main

import "sync"

type connectionRegistry struct {
	mu            sync.RWMutex
	byUser        map[string]map[string]*connection
	byDevice      map[string]*connection
	byConnection  map[string]*connection
}

func newConnectionRegistry() *connectionRegistry {
	return &connectionRegistry{
		byUser:       make(map[string]map[string]*connection),
		byDevice:     make(map[string]*connection),
		byConnection: make(map[string]*connection),
	}
}

func (registry *connectionRegistry) register(candidate *connection) *connection {
	registry.mu.Lock()
	defer registry.mu.Unlock()
	previous := registry.byDevice[candidate.deviceID]
	if previous != nil {
		registry.removeLocked(previous)
	}
	devices := registry.byUser[candidate.userID]
	if devices == nil {
		devices = make(map[string]*connection)
		registry.byUser[candidate.userID] = devices
	}
	devices[candidate.deviceID] = candidate
	registry.byDevice[candidate.deviceID] = candidate
	registry.byConnection[candidate.connectionID] = candidate
	return previous
}

func (registry *connectionRegistry) unregister(candidate *connection) bool {
	registry.mu.Lock()
	defer registry.mu.Unlock()
	if registry.byConnection[candidate.connectionID] != candidate {
		return false
	}
	registry.removeLocked(candidate)
	return true
}

func (registry *connectionRegistry) removeLocked(candidate *connection) {
	delete(registry.byConnection, candidate.connectionID)
	if registry.byDevice[candidate.deviceID] == candidate {
		delete(registry.byDevice, candidate.deviceID)
	}
	if devices := registry.byUser[candidate.userID]; devices != nil {
		if devices[candidate.deviceID] == candidate {
			delete(devices, candidate.deviceID)
		}
		if len(devices) == 0 {
			delete(registry.byUser, candidate.userID)
		}
	}
}

func (registry *connectionRegistry) forUser(userID string) []*connection {
	registry.mu.RLock()
	defer registry.mu.RUnlock()
	devices := registry.byUser[userID]
	connections := make([]*connection, 0, len(devices))
	for _, candidate := range devices {
		connections = append(connections, candidate)
	}
	return connections
}

func (registry *connectionRegistry) forDevice(deviceID string) *connection {
	registry.mu.RLock()
	defer registry.mu.RUnlock()
	return registry.byDevice[deviceID]
}

func (registry *connectionRegistry) all() []*connection {
	registry.mu.RLock()
	defer registry.mu.RUnlock()
	connections := make([]*connection, 0, len(registry.byConnection))
	for _, candidate := range registry.byConnection {
		connections = append(connections, candidate)
	}
	return connections
}

func (registry *connectionRegistry) count() int {
	registry.mu.RLock()
	defer registry.mu.RUnlock()
	return len(registry.byConnection)
}
