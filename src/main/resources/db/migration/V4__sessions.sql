-- One row per signed-in device. The access JWT carries the session id as `sid`.
CREATE TABLE sessions (
    id                    UUID PRIMARY KEY DEFAULT uuidv7(),
    user_id               UUID NOT NULL REFERENCES users (id),
    auth_method           TEXT NOT NULL CHECK (auth_method IN ('GOOGLE', 'APPLE', 'WHATSAPP')),
    device_name           TEXT,
    user_agent            TEXT,
    ip_at_creation        TEXT,
    created_at_epoch_ms   BIGINT NOT NULL,
    last_used_at_epoch_ms BIGINT NOT NULL,
    expires_at_epoch_ms   BIGINT NOT NULL,   -- absolute cap; refreshing never extends it
    revoked_at_epoch_ms   BIGINT,
    revoked_reason        TEXT CHECK (revoked_reason IN ('LOGOUT', 'LOGOUT_ALL', 'REVOKED_BY_USER', 'REUSE_DETECTED')),
    CHECK ((revoked_at_epoch_ms IS NULL) = (revoked_reason IS NULL))
);
CREATE INDEX sessions_user_active_idx ON sessions (user_id) WHERE revoked_at_epoch_ms IS NULL;

-- Opaque refresh tokens, stored as SHA-256. Each refresh marks the presented token used and
-- issues a child; presenting a used token again (outside a short grace window) revokes the session.
CREATE TABLE refresh_tokens (
    id                  UUID PRIMARY KEY DEFAULT uuidv7(),
    session_id          UUID NOT NULL REFERENCES sessions (id),
    token_hash          BYTEA NOT NULL UNIQUE,
    parent_id           UUID REFERENCES refresh_tokens (id),
    created_at_epoch_ms BIGINT NOT NULL,
    expires_at_epoch_ms BIGINT NOT NULL,
    used_at_epoch_ms    BIGINT
);
CREATE INDEX refresh_tokens_session_idx ON refresh_tokens (session_id);
CREATE INDEX refresh_tokens_parent_idx ON refresh_tokens (parent_id);
