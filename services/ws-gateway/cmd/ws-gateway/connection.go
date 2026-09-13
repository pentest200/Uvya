package main

import (
	"sync"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"
)

type connection struct {
	ws           *websocket.Conn
	gateway      *gateway
	userID       string
	deviceID     string
	sessionID    string
	accessToken  string
	connectionID string
	outbound     chan []byte
	done         chan struct{}
	closed       chan struct{}
	closeOnce    sync.Once
	disconnectOnce sync.Once
	closeMu      sync.RWMutex
	closeCode    int
	closeReason  string
	lastPongUnix atomic.Int64
}

func newConnection(ws *websocket.Conn, gateway *gateway, identity authContext) *connection {
	connection := &connection{
		ws:           ws,
		gateway:      gateway,
		userID:       identity.UserID,
		deviceID:     identity.DeviceID,
		sessionID:    identity.SessionID,
		accessToken:  identity.AccessToken,
		connectionID: newRequestID(),
		outbound:     make(chan []byte, gateway.cfg.sendQueueSize),
		done:         make(chan struct{}),
		closed:       make(chan struct{}),
		closeCode:    websocket.CloseNormalClosure,
		closeReason:  "connection closed",
	}
	connection.lastPongUnix.Store(time.Now().UnixNano())
	return connection
}

func (connection *connection) start() {
	connection.ws.SetReadLimit(connection.gateway.cfg.maxMessageBytes)
	_ = connection.ws.SetReadDeadline(time.Now().Add(connection.gateway.cfg.heartbeatTimeout))
	connection.ws.SetPongHandler(func(string) error {
		connection.lastPongUnix.Store(time.Now().UnixNano())
		return connection.ws.SetReadDeadline(time.Now().Add(connection.gateway.cfg.heartbeatTimeout))
	})
	connection.ws.SetPingHandler(func(applicationData string) error {
		return connection.ws.WriteControl(websocket.PongMessage, []byte(applicationData),
			time.Now().Add(connection.gateway.cfg.writeTimeout))
	})
	go connection.writePump()
	go connection.readPump()
}

func (connection *connection) readPump() {
	defer func() {
		connection.gateway.disconnect(connection, websocket.CloseNormalClosure, "client disconnected")
	}()
	for {
		_, payload, err := connection.ws.ReadMessage()
		if err != nil {
			return
		}
		connection.gateway.handleClientMessage(connection, payload)
	}
}

func (connection *connection) writePump() {
	ticker := time.NewTicker(connection.gateway.cfg.heartbeatInterval)
	defer func() {
		ticker.Stop()
		_ = connection.ws.Close()
		close(connection.closed)
	}()
	for {
		select {
		case payload := <-connection.outbound:
			if err := connection.ws.SetWriteDeadline(time.Now().Add(connection.gateway.cfg.writeTimeout)); err != nil {
				return
			}
			if err := connection.ws.WriteMessage(websocket.TextMessage, payload); err != nil {
				return
			}
			connection.gateway.metrics.messagesSent.Add(1)
		case <-ticker.C:
			select {
			case <-connection.done:
				connection.closeWebSocket()
				return
			default:
			}
			if _, err := connection.gateway.validator.Validate(connection.accessToken); err != nil {
				connection.gateway.disconnect(connection, websocket.ClosePolicyViolation, "access token is no longer valid")
				return
			}
			lastPong := time.Unix(0, connection.lastPongUnix.Load())
			if time.Since(lastPong) > connection.gateway.cfg.heartbeatTimeout {
				connection.gateway.metrics.heartbeatMisses.Add(1)
				connection.gateway.disconnect(connection, websocket.CloseGoingAway, "heartbeat timeout")
				return
			}
			if err := connection.ws.WriteControl(websocket.PingMessage, nil,
				time.Now().Add(connection.gateway.cfg.writeTimeout)); err != nil {
				return
			}
			connection.gateway.refreshMetadata(connection)
		case <-connection.done:
			connection.closeWebSocket()
			return
		}
	}
}

func (connection *connection) enqueue(payload []byte) bool {
	select {
	case <-connection.done:
		return false
	default:
	}
	select {
	case connection.outbound <- payload:
		return true
	default:
		connection.gateway.metrics.backpressure.Add(1)
		connection.gateway.disconnect(connection, websocket.CloseTryAgainLater, "backpressure")
		return false
	}
}

func (connection *connection) requestClose(code int, reason string) {
	connection.closeMu.Lock()
	connection.closeCode = code
	connection.closeReason = reason
	connection.closeMu.Unlock()
	connection.closeOnce.Do(func() { close(connection.done) })
}

func (connection *connection) closeWebSocket() {
	connection.closeMu.RLock()
	code := connection.closeCode
	reason := connection.closeReason
	connection.closeMu.RUnlock()
	_ = connection.ws.WriteControl(websocket.CloseMessage, websocket.FormatCloseMessage(code, reason),
		time.Now().Add(connection.gateway.cfg.writeTimeout))
}
