-- Account deletion: the users row stays as a scrubbed tombstone (no contact data, generic name)
-- so owner_user_id foreign keys on soft-deleted cricket data stay valid.
ALTER TABLE users
    ADD COLUMN status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DELETED')),
    ADD COLUMN deleted_at_epoch_ms BIGINT,
    ADD CONSTRAINT users_deleted_consistent CHECK ((status = 'DELETED') = (deleted_at_epoch_ms IS NOT NULL));

ALTER TABLE sessions DROP CONSTRAINT sessions_revoked_reason_check;
ALTER TABLE sessions ADD CONSTRAINT sessions_revoked_reason_check
    CHECK (revoked_reason IN ('LOGOUT', 'LOGOUT_ALL', 'REVOKED_BY_USER', 'REUSE_DETECTED', 'PROVIDER_REVOKED', 'ACCOUNT_DELETED'));

-- Apple refresh tokens, kept only so they can be revoked at Apple when the user deletes their
-- account or unlinks Apple. Encrypted with AES-256-GCM (key from APPLE_TOKEN_ENCRYPTION_KEY),
-- the identity id bound in as associated data. Removed with the identity.
CREATE TABLE apple_tokens (
    identity_id           UUID PRIMARY KEY REFERENCES user_identities (id) ON DELETE CASCADE,
    client_id             TEXT NOT NULL,     -- the `aud` the code was issued to; revoke must use the same
    refresh_token_cipher  BYTEA NOT NULL,    -- 12-byte IV || ciphertext+tag
    created_at_epoch_ms   BIGINT NOT NULL,
    updated_at_epoch_ms   BIGINT NOT NULL
);
