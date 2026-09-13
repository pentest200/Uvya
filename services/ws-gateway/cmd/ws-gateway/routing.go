package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"sort"
	"strings"
)

type persistedMessage struct {
	MessageID string `json:"messageId"`
	ChatID    string `json:"chatId"`
	Sequence  int64  `json:"sequence"`
}

type messageRouter interface {
	Persist(context.Context, authContext, clientEnvelope) (persistedMessage, error)
}

type apiMessageRouter struct {
	client  *http.Client
	baseURL string
}

func newAPIMessageRouter(cfg config) *apiMessageRouter {
	return &apiMessageRouter{client: &http.Client{Timeout: cfg.apiTimeout}, baseURL: strings.TrimRight(cfg.apiBaseURL, "/")}
}

func (router *apiMessageRouter) Persist(ctx context.Context, auth authContext, message clientEnvelope) (persistedMessage, error) {
	payload, err := normalizeMessagePayload(message.Payload)
	if err != nil {
		return persistedMessage{}, err
	}
	payload.ClientMessageID = message.ClientMessageID
	body, err := json.Marshal(payload)
	if err != nil {
		return persistedMessage{}, errors.New("message payload is invalid")
	}
	endpoint := router.baseURL + "/v1/chats/" + url.PathEscape(message.ChatID) + "/messages"
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(body))
	if err != nil {
		return persistedMessage{}, errors.New("unable to create message request")
	}
	request.Header.Set("Authorization", "Bearer "+auth.AccessToken)
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Idempotency-Key", message.ClientMessageID)
	request.Header.Set(requestIDHeader, message.RequestID)
	response, err := router.client.Do(request)
	if err != nil {
		return persistedMessage{}, errors.New("message service is unavailable")
	}
	defer response.Body.Close()
	if response.StatusCode < http.StatusOK || response.StatusCode >= http.StatusMultipleChoices {
		return persistedMessage{}, fmt.Errorf("message service rejected request (%d)", response.StatusCode)
	}
	var result persistedMessage
	if err := json.NewDecoder(io.LimitReader(response.Body, 64*1024)).Decode(&result); err != nil {
		return persistedMessage{}, errors.New("message service returned an invalid response")
	}
	if !isUUID(result.MessageID) || !isUUID(result.ChatID) || result.ChatID != message.ChatID || result.Sequence < 1 {
		return persistedMessage{}, errors.New("message service returned incomplete message identity")
	}
	return result, nil
}

type normalizedMessagePayload struct {
	ClientMessageID     string            `json:"clientMessageId"`
	Type                string            `json:"type"`
	Body                string            `json:"body"`
	ReplyToMessageID    string            `json:"replyToMessageId,omitempty"`
	ForwardedFromID     string            `json:"forwardedFromMessageId,omitempty"`
	Attachments         []json.RawMessage `json:"attachments,omitempty"`
}

func normalizeMessagePayload(raw json.RawMessage) (normalizedMessagePayload, error) {
	var fields map[string]json.RawMessage
	if err := json.Unmarshal(raw, &fields); err != nil || fields == nil {
		return normalizedMessagePayload{}, errors.New("payload must be an object")
	}
	messageType := rawString(fields, "type")
	if messageType == "" {
		messageType = rawString(fields, "messageType")
	}
	body := rawString(fields, "body")
	if messageType == "" {
		messageType = "text"
	}
	if !strings.EqualFold(messageType, "text") || strings.TrimSpace(body) == "" || len(body) > 16000 {
		return normalizedMessagePayload{}, errors.New("payload requires a non-empty text body")
	}
	result := normalizedMessagePayload{Type: "text", Body: body}
	result.ReplyToMessageID = rawString(fields, "replyToMessageId")
	result.ForwardedFromID = rawString(fields, "forwardedFromMessageId")
	if attachments, ok := fields["attachments"]; ok {
		if err := json.Unmarshal(attachments, &result.Attachments); err != nil {
			return normalizedMessagePayload{}, errors.New("attachments is invalid")
		}
	}
	return result, nil
}

func rawString(fields map[string]json.RawMessage, name string) string {
	var value string
	if raw, ok := fields[name]; ok {
		_ = json.Unmarshal(raw, &value)
	}
	return value
}

type commandRouter interface {
	Handle(context.Context, authContext, clientEnvelope) error
}

type noopCommandRouter struct{}

func (noopCommandRouter) Handle(context.Context, authContext, clientEnvelope) error { return nil }

type syncProvider interface {
	Resume(context.Context, authContext, syncRequest) (outboundEnvelope, error)
}

type apiSyncProvider struct {
	client  *http.Client
	baseURL string
}

func newAPISyncProvider(cfg config) *apiSyncProvider {
	return &apiSyncProvider{client: &http.Client{Timeout: cfg.apiTimeout}, baseURL: strings.TrimRight(cfg.apiBaseURL, "/")}
}

func (provider *apiSyncProvider) Resume(ctx context.Context, auth authContext, request syncRequest) (outboundEnvelope, error) {
	delta := outboundEnvelope{Type: syncDelta, RequestID: request.RequestID,
		GlobalSyncCursor: request.GlobalSyncCursor,
		PerChatCursors:   cloneCursors(request.PerChatCursors),
		LastAcknowledgedClientMessage: request.LastAcknowledgedClientMessage}
	chatIDs := make([]string, 0, len(request.PerChatCursors))
	for chatID := range request.PerChatCursors {
		chatIDs = append(chatIDs, chatID)
	}
	sort.Strings(chatIDs)
	for _, chatID := range chatIDs {
		cursor := request.PerChatCursors[chatID]
		messages, nextCursor, err := provider.resumeChat(ctx, auth, chatID, cursor)
		if err != nil {
			return outboundEnvelope{}, err
		}
		delta.Messages = append(delta.Messages, messages...)
		if nextCursor != "" {
			delta.PerChatCursors[chatID] = nextCursor
		}
	}
	return delta, nil
}

type apiHistoryResponse struct {
	Messages   []json.RawMessage `json:"messages"`
	NextCursor string            `json:"nextCursor"`
	HasMore    bool              `json:"hasMore"`
}

func (provider *apiSyncProvider) resumeChat(ctx context.Context, auth authContext, chatID, cursor string) ([]json.RawMessage, string, error) {
	endpoint := provider.baseURL + "/v1/chats/" + url.PathEscape(chatID) + "/messages?after=" + url.QueryEscape(cursor) + "&size=100"
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return nil, "", errors.New("unable to create synchronization request")
	}
	request.Header.Set("Authorization", "Bearer "+auth.AccessToken)
	response, err := provider.client.Do(request)
	if err != nil {
		return nil, "", errors.New("message service is unavailable")
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return nil, "", fmt.Errorf("synchronization rejected request (%d)", response.StatusCode)
	}
	var history apiHistoryResponse
	if err := json.NewDecoder(io.LimitReader(response.Body, 4*1024*1024)).Decode(&history); err != nil {
		return nil, "", errors.New("message service returned invalid synchronization data")
	}
	next := history.NextCursor
	if next == "" && len(history.Messages) > 0 {
		next = maxMessageSequence(history.Messages, cursor)
	}
	return history.Messages, next, nil
}

func maxMessageSequence(messages []json.RawMessage, fallback string) string {
	maximum := int64(-1)
	for _, raw := range messages {
		var message struct {
			Sequence int64 `json:"sequence"`
		}
		if json.Unmarshal(raw, &message) == nil && message.Sequence > maximum {
			maximum = message.Sequence
		}
	}
	if maximum < 0 {
		return fallback
	}
	return fmt.Sprintf("%d", maximum)
}

func cloneCursors(cursors map[string]string) map[string]string {
	clone := make(map[string]string, len(cursors))
	for chatID, cursor := range cursors {
		clone[chatID] = cursor
	}
	return clone
}
