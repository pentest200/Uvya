-- User profiles are separate from authentication credentials so profile changes
-- cannot accidentally alter password/session state.
CREATE TABLE user_profiles (
    user_id UUID PRIMARY KEY REFERENCES app_users(id) ON DELETE CASCADE,
    username VARCHAR(32),
    username_normalized VARCHAR(32),
    display_name VARCHAR(120),
    bio VARCHAR(500),
    avatar_metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    privacy_settings JSONB NOT NULL DEFAULT '{}'::jsonb,
    discoverability VARCHAR(24) NOT NULL DEFAULT 'CONTACTS_ONLY',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_user_profiles_username_normalized UNIQUE (username_normalized),
    CONSTRAINT ck_user_profiles_username CHECK (
        username IS NULL OR username ~ '^[A-Za-z0-9_]{3,32}$'
    ),
    CONSTRAINT ck_user_profiles_username_normalized CHECK (
        username_normalized IS NULL OR username_normalized ~ '^[a-z0-9_]{3,32}$'
    ),
    CONSTRAINT ck_user_profiles_discoverability CHECK (
        discoverability IN ('PUBLIC', 'CONTACTS_ONLY', 'NOBODY')
    )
);

CREATE INDEX ix_user_profiles_discoverable_username
    ON user_profiles(discoverability, username_normalized);

CREATE TABLE user_identifiers (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    identifier_type VARCHAR(16) NOT NULL,
    identifier_hash CHAR(64) NOT NULL,
    verified BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_user_identifiers_type_hash UNIQUE (identifier_type, identifier_hash),
    CONSTRAINT uq_user_identifiers_user_type UNIQUE (user_id, identifier_type),
    CONSTRAINT ck_user_identifiers_type CHECK (identifier_type IN ('EMAIL', 'PHONE'))
);

CREATE INDEX ix_user_identifiers_user ON user_identifiers(user_id, identifier_type);

CREATE TABLE contact_identifiers (
    id UUID PRIMARY KEY,
    owner_user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    identifier_type VARCHAR(16) NOT NULL,
    identifier_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_contact_identifiers_owner_value
        UNIQUE (owner_user_id, identifier_type, identifier_hash),
    CONSTRAINT ck_contact_identifiers_type CHECK (identifier_type IN ('EMAIL', 'PHONE'))
);

CREATE INDEX ix_contact_identifiers_owner ON contact_identifiers(owner_user_id, created_at);
CREATE INDEX ix_contact_identifiers_hash ON contact_identifiers(identifier_type, identifier_hash);
