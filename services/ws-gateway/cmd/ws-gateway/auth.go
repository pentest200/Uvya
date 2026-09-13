package main

import (
	"crypto/rsa"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"math/big"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

var errAuthUnavailable = errors.New("access-token validation is not configured")

type authContext struct {
	UserID      string
	DeviceID    string
	SessionID   string
	AccessToken string
}

type accessClaims struct {
	jwt.RegisteredClaims
	SessionID string `json:"sid"`
	DeviceID  string `json:"did"`
	Scope     string `json:"scope"`
}

type accessTokenValidator struct {
	mu        sync.RWMutex
	publicKey *rsa.PublicKey
	issuer    string
	jwksURL   string
	client    *http.Client
	fetchedAt time.Time
	staticKey bool
}

func newAccessTokenValidator(cfg config) *accessTokenValidator {
	key, err := loadRSAPublicKey(cfg.jwtPublicKeyBase64, cfg.jwtPublicKeyFile)
	if err != nil {
		key = nil
	}
	return &accessTokenValidator{publicKey: key, issuer: cfg.jwtIssuer, jwksURL: cfg.jwtJwksURL,
		client: &http.Client{Timeout: 3 * time.Second}, staticKey: key != nil}
}

func (validator *accessTokenValidator) Validate(rawToken string) (authContext, error) {
	if validator.publicKeyForValidation() == nil {
		return authContext{}, errAuthUnavailable
	}
	if strings.TrimSpace(rawToken) == "" {
		return authContext{}, errors.New("access token is required")
	}
	claims := &accessClaims{}
	options := []jwt.ParserOption{jwt.WithValidMethods([]string{jwt.SigningMethodRS256.Alg()})}
	if validator.issuer != "" {
		options = append(options, jwt.WithIssuer(validator.issuer))
	}
	key := validator.publicKeyForValidation()
	token, err := validator.parse(rawToken, claims, key, options...)
	if err != nil && validator.refreshKey() {
		claims = &accessClaims{}
		token, err = validator.parse(rawToken, claims, validator.publicKeyForValidation(), options...)
	}
	if err != nil || token == nil || !token.Valid {
		return authContext{}, errors.New("access token is invalid")
	}
	if !isUUID(claims.Subject) || !isUUID(claims.DeviceID) || !isUUID(claims.SessionID) {
		return authContext{}, errors.New("access token is missing identity claims")
	}
	if !hasScope(claims.Scope, "user") {
		return authContext{}, errors.New("access token scope is invalid")
	}
	return authContext{UserID: claims.Subject, DeviceID: claims.DeviceID, SessionID: claims.SessionID,
		AccessToken: rawToken}, nil
}

func (validator *accessTokenValidator) parse(rawToken string, claims *accessClaims, key *rsa.PublicKey,
	options ...jwt.ParserOption) (*jwt.Token, error) {
	return jwt.ParseWithClaims(rawToken, claims, func(token *jwt.Token) (any, error) {
		if token.Method != jwt.SigningMethodRS256 {
			method := "unknown"
			if token.Method != nil {
				method = token.Method.Alg()
			}
			return nil, fmt.Errorf("unexpected signing method %s", method)
		}
		return key, nil
	}, options...)
}

func (validator *accessTokenValidator) publicKeyForValidation() *rsa.PublicKey {
	validator.mu.RLock()
	key := validator.publicKey
	fetchedAt := validator.fetchedAt
	validator.mu.RUnlock()
	if key != nil && (validator.jwksURL == "" || time.Since(fetchedAt) < 5*time.Minute) {
		return key
	}
	if validator.refreshKey() {
		validator.mu.RLock()
		key = validator.publicKey
		validator.mu.RUnlock()
	}
	return key
}

func (validator *accessTokenValidator) refreshKey() bool {
	if validator.staticKey || validator.jwksURL == "" {
		return false
	}
	request, err := http.NewRequest(http.MethodGet, validator.jwksURL, nil)
	if err != nil {
		return false
	}
	response, err := validator.client.Do(request)
	if err != nil {
		return false
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return false
	}
	var document struct {
		Keys []json.RawMessage `json:"keys"`
	}
	if err := json.NewDecoder(io.LimitReader(response.Body, 256*1024)).Decode(&document); err != nil {
		return false
	}
	for _, raw := range document.Keys {
		var key struct {
			KTY string `json:"kty"`
			N   string `json:"n"`
			E   string `json:"e"`
		}
		if json.Unmarshal(raw, &key) != nil || key.KTY != "RSA" || key.N == "" || key.E == "" {
			continue
		}
		modulus, err := base64.RawURLEncoding.DecodeString(key.N)
		if err != nil {
			continue
		}
		exponentBytes, err := base64.RawURLEncoding.DecodeString(key.E)
		if err != nil {
			continue
		}
		exponent := new(big.Int).SetBytes(exponentBytes)
		if !exponent.IsInt64() || exponent.Int64() <= 0 {
			continue
		}
		validator.mu.Lock()
		validator.publicKey = &rsa.PublicKey{N: new(big.Int).SetBytes(modulus), E: int(exponent.Int64())}
		validator.fetchedAt = time.Now()
		validator.mu.Unlock()
		return true
	}
	return false
}

func loadRSAPublicKey(encoded, fileName string) (*rsa.PublicKey, error) {
	value := strings.TrimSpace(encoded)
	if value == "" && fileName != "" {
		contents, err := os.ReadFile(fileName)
		if err != nil {
			return nil, err
		}
		value = strings.TrimSpace(string(contents))
	}
	if value == "" {
		return nil, errAuthUnavailable
	}
	value = strings.TrimSpace(value)
	var keyBytes []byte
	if block, _ := pem.Decode([]byte(value)); block != nil {
		keyBytes = block.Bytes
	} else {
		decoded, err := base64.StdEncoding.DecodeString(strings.Join(strings.Fields(value), ""))
		if err != nil {
			return nil, errors.New("JWT public key is not valid base64 or PEM")
		}
		keyBytes = decoded
	}
	parsed, err := x509.ParsePKIXPublicKey(keyBytes)
	if err != nil {
		return nil, fmt.Errorf("JWT public key is invalid: %w", err)
	}
	key, ok := parsed.(*rsa.PublicKey)
	if !ok {
		return nil, errors.New("JWT public key must be RSA")
	}
	return key, nil
}

func extractAccessToken(request *http.Request) string {
	if value := request.Header.Get("Authorization"); strings.HasPrefix(strings.ToLower(value), "bearer ") {
		return strings.TrimSpace(value[len("Bearer "):])
	}
	if value := request.URL.Query().Get("access_token"); value != "" {
		return value
	}
	for _, protocol := range strings.Split(request.Header.Get("Sec-WebSocket-Protocol"), ",") {
		protocol = strings.TrimSpace(protocol)
		if strings.HasPrefix(protocol, "bearer.") {
			return strings.TrimPrefix(protocol, "bearer.")
		}
	}
	return ""
}

func hasScope(scope, expected string) bool {
	for _, value := range strings.Fields(scope) {
		if value == expected {
			return true
		}
	}
	return false
}
