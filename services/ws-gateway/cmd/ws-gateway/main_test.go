package main

import (
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestHealthEndpointsReturnUp(t *testing.T) {
	handler := newHandler(slog.New(slog.NewTextHandler(io.Discard, nil)))

	for _, path := range []string{"/health/live", "/health/ready"} {
		t.Run(path, func(t *testing.T) {
			request := httptest.NewRequest(http.MethodGet, path, nil)
			request.Header.Set(requestIDHeader, "test-request-123")
			response := httptest.NewRecorder()

			handler.ServeHTTP(response, request)

			if response.Code != http.StatusOK {
				t.Fatalf("expected status 200, got %d", response.Code)
			}
			if got := response.Header().Get(requestIDHeader); got != "test-request-123" {
				t.Fatalf("expected request ID to be preserved, got %q", got)
			}
		})
	}
}

func TestUnsafeRequestIDIsReplaced(t *testing.T) {
	handler := newHandler(slog.New(slog.NewTextHandler(io.Discard, nil)))
	request := httptest.NewRequest(http.MethodGet, "/health/live", nil)
	request.Header.Set(requestIDHeader, "unsafe value")
	response := httptest.NewRecorder()

	handler.ServeHTTP(response, request)

	if response.Code != http.StatusOK {
		t.Fatalf("expected status 200, got %d", response.Code)
	}
	if got := response.Header().Get(requestIDHeader); got == "unsafe value" || !isSafeRequestID(got) {
		t.Fatalf("expected a safe generated request ID, got %q", got)
	}
}

func TestIsSafeRequestID(t *testing.T) {
	for _, test := range []struct {
		name  string
		value string
		valid bool
	}{
		{name: "empty", value: "", valid: false},
		{name: "safe", value: "abc-123:trace_id", valid: true},
		{name: "space", value: "abc 123", valid: false},
		{name: "too long", value: string(make([]byte, 129)), valid: false},
	} {
		t.Run(test.name, func(t *testing.T) {
			if got := isSafeRequestID(test.value); got != test.valid {
				t.Fatalf("isSafeRequestID(%q) = %t, want %t", test.value, got, test.valid)
			}
		})
	}
}
