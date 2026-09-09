CREATE TABLE app_users (
    id UUID PRIMARY KEY,
    email VARCHAR(320) NOT NULL,
    password_hash VARCHAR(255),
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    failed_login_attempts INTEGER NOT NULL DEFAULT 0,
    locked_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_app_users_email UNIQUE (email),
    CONSTRAINT ck_app_users_status CHECK (status IN ('ACTIVE', 'LOCKED', 'DISABLED'))
);

CREATE TABLE devices (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_users(id),
    name VARCHAR(120) NOT NULL,
    user_agent VARCHAR(512),
    last_ip VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ
);

CREATE INDEX ix_devices_user_id ON devices(user_id);

CREATE TABLE auth_sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_users(id),
    device_id UUID NOT NULL REFERENCES devices(id),
    refresh_token_hash CHAR(64) NOT NULL,
    refresh_token_expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    last_used_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    revoke_reason VARCHAR(64),
    replaced_by_session_id UUID REFERENCES auth_sessions(id),
    CONSTRAINT uq_auth_sessions_refresh_token_hash UNIQUE (refresh_token_hash)
);

CREATE INDEX ix_auth_sessions_user_id ON auth_sessions(user_id);
CREATE INDEX ix_auth_sessions_device_id ON auth_sessions(device_id);

CREATE TABLE auth_audit_logs (
    id UUID PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    user_id UUID REFERENCES app_users(id),
    device_id UUID REFERENCES devices(id),
    session_id UUID REFERENCES auth_sessions(id),
    ip_address VARCHAR(64),
    request_id VARCHAR(128),
    metadata TEXT NOT NULL DEFAULT '{}',
    occurred_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_auth_audit_logs_user_id_occurred_at ON auth_audit_logs(user_id, occurred_at);
CREATE INDEX ix_auth_audit_logs_event_type_occurred_at ON auth_audit_logs(event_type, occurred_at);
