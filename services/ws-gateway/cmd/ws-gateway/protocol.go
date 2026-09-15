package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"strings"
	"time"
)

const (
	messageSend       = "message.send"
	messagePersisted  = "message.persisted"
	messageNew       = "message.new"
	messageDelivered = "message.delivered"
	messageRead      = "message.read"
	typingStart      = "typing.start"
	typingStop       = "typing.stop"
	presenceSubscribe = "presence.subscribe"
	presenceUnsubscribe = "presence.unsubscribe"
	presenceSet       = "presence.set"
	presenceUpdated  = "presence.updated"
	syncResume       = "sync.resume"
	syncDelta        = "sync.delta"
	heartbeat        = "heartbeat"
	heartbeatAck     = "heartbeat.ack"
	errorMessage     = "error"
)

type clientEnvelope struct {
	Type                          string            `json:"type"`
	RequestID                     string            `json:"requestId"`
	ChatID                        string            `json:"chatId"`
	MessageID                     string            `json:"messageId"`
	ClientMessageID               string            `json:"clientMessageId"`
	DeviceID                      string            `json:"deviceId"`
	UserIDs                       []string          `json:"userIds"`
	Visibility                    string            `json:"visibility"`
	Activity                      string            `json:"activity"`
	GlobalSyncCursor              string            `json:"globalSyncCursor"`
	PerChatCursors                map[string]string `json:"perChatCursors"`
	LastAcknowledgedClientMessage string            `json:"lastAcknowledgedClientMessageId"`
	Payload                       json.RawMessage   `json:"payload"`
	globalCursorPresent           bool
	perChatCursorsPresent         bool
	lastAcknowledgedPresent      bool
}

type outboundEnvelope struct {
	Type                          string            `json:"type"`
	RequestID                     string            `json:"requestId,omitempty"`
	MessageID                     string            `json:"messageId,omitempty"`
	ChatID                        string            `json:"chatId,omitempty"`
	UserID                        string            `json:"userId,omitempty"`
	DeviceID                      string            `json:"deviceId,omitempty"`
	Status                        string            `json:"status,omitempty"`
	Visibility                    string            `json:"visibility,omitempty"`
	Activity                      string            `json:"activity,omitempty"`
	LastSeen                      *time.Time        `json:"lastSeen,omitempty"`
	ExpiresAt                     *time.Time        `json:"expiresAt,omitempty"`
	Sequence                      int64             `json:"sequence,omitempty"`
	GlobalSyncCursor              string            `json:"globalSyncCursor,omitempty"`
	PerChatCursors                map[string]string `json:"perChatCursors,omitempty"`
	LastAcknowledgedClientMessage string            `json:"lastAcknowledgedClientMessageId,omitempty"`
	Messages                      []json.RawMessage `json:"messages,omitempty"`
	Payload                       json.RawMessage   `json:"payload,omitempty"`
	Error                         *protocolError    `json:"error,omitempty"`
}

type protocolError struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

type syncRequest struct {
	RequestID                     string
	DeviceID                      string
	GlobalSyncCursor              string
	PerChatCursors                map[string]string
	LastAcknowledgedClientMessage string
}

func decodeClientEnvelope(data []byte) (clientEnvelope, error) {
	var message clientEnvelope
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&message); err != nil {
		return clientEnvelope{}, fmt.Errorf("invalid JSON message: %w", err)
	}
	var extra any
	if err := decoder.Decode(&extra); err != io.EOF {
		return clientEnvelope{}, errors.New("message contains multiple JSON values")
	}
	var fields map[string]json.RawMessage
	if err := json.Unmarshal(data, &fields); err != nil {
		return clientEnvelope{}, errors.New("message must be a JSON object")
	}
	_, message.globalCursorPresent = fields["globalSyncCursor"]
	_, message.perChatCursorsPresent = fields["perChatCursors"]
	_, message.lastAcknowledgedPresent = fields["lastAcknowledgedClientMessageId"]
	if strings.TrimSpace(message.Type) == "" {
		return clientEnvelope{}, errors.New("type is required")
	}
	if message.RequestID != "" && !isSafeRequestID(message.RequestID) {
		return clientEnvelope{}, errors.New("requestId is invalid")
	}
	return message, nil
}

func validateSyncRequest(message clientEnvelope, authenticatedDevice string) (syncRequest, error) {
	if !isUUID(message.DeviceID) || message.DeviceID != authenticatedDevice {
		return syncRequest{}, errors.New("deviceId must match the authenticated device")
	}
	if !message.globalCursorPresent || !message.perChatCursorsPresent || !message.lastAcknowledgedPresent {
		return syncRequest{}, errors.New("deviceId, globalSyncCursor, perChatCursors, and lastAcknowledgedClientMessageId are required")
	}
	if message.PerChatCursors == nil {
		return syncRequest{}, errors.New("perChatCursors must be an object")
	}
	if len(message.PerChatCursors) > 1000 {
		return syncRequest{}, errors.New("perChatCursors exceeds the limit")
	}
	for chatID, cursor := range message.PerChatCursors {
		if !isUUID(chatID) || len(cursor) > 256 {
			return syncRequest{}, errors.New("perChatCursors contains an invalid entry")
		}
	}
	if len(message.GlobalSyncCursor) > 256 || len(message.LastAcknowledgedClientMessage) > 128 {
		return syncRequest{}, errors.New("sync cursor is too long")
	}
	if message.LastAcknowledgedClientMessage != "" && !isUUID(message.LastAcknowledgedClientMessage) {
		return syncRequest{}, errors.New("lastAcknowledgedClientMessageId must be a UUID")
	}
	return syncRequest{
		RequestID:                     message.RequestID,
		DeviceID:                      message.DeviceID,
		GlobalSyncCursor:              message.GlobalSyncCursor,
		PerChatCursors:                message.PerChatCursors,
		LastAcknowledgedClientMessage: message.LastAcknowledgedClientMessage,
	}, nil
}

func errorEnvelope(requestID, code, message string) []byte {
	encoded, err := json.Marshal(outboundEnvelope{
		Type:      errorMessage,
		RequestID: requestID,
		Error:     &protocolError{Code: code, Message: message},
	})
	if err != nil {
		return []byte(`{"type":"error","error":{"code":"internal_error","message":"internal server error"}}`)
	}
	return encoded
}

func marshalEvent(event outboundEnvelope) []byte {
	encoded, err := json.Marshal(event)
	if err != nil {
		return errorEnvelope(event.RequestID, "internal_error", "unable to encode event")
	}
	return encoded
}

func isUUID(value string) bool {
	if len(value) != 36 {
		return false
	}
	for index, character := range value {
		if index == 8 || index == 13 || index == 18 || index == 23 {
			if character != '-' {
				return false
			}
			continue
		}
		if !((character >= '0' && character <= '9') ||
			(character >= 'a' && character <= 'f') ||
			(character >= 'A' && character <= 'F')) {
			return false
		}
	}
	return true
}
