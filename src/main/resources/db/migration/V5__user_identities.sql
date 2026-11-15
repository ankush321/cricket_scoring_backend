-- A user is the account; identities are the ways to prove you own it (Google, Apple, WhatsApp).
-- UNIQUE (provider, provider_subject) is the duplicate-account guard.
CREATE TABLE user_identities (
    id                    UUID PRIMARY KEY DEFAULT uuidv7(),
    user_id               UUID NOT NULL REFERENCES users (id),
    provider              TEXT NOT NULL CHECK (provider IN ('GOOGLE', 'APPLE', 'WHATSAPP')),
    provider_subject      TEXT NOT NULL,        -- Google/Apple `sub`, or the E.164 phone for WhatsApp
    email                 TEXT,                 -- lowercase, as reported by the provider
    email_verified        BOOLEAN NOT NULL DEFAULT FALSE,
    is_private_relay      BOOLEAN NOT NULL DEFAULT FALSE,  -- Apple Hide My Email
    phone_e164            TEXT,
    display_name          TEXT,
    avatar_url            TEXT,
    created_at_epoch_ms   BIGINT NOT NULL,
    last_used_at_epoch_ms BIGINT NOT NULL,
    UNIQUE (provider, provider_subject),
    UNIQUE (user_id, provider)                  -- one identity per provider per account
);

-- Backfill from the columns V2 put on users.
INSERT INTO user_identities (user_id, provider, provider_subject, email, email_verified, created_at_epoch_ms, last_used_at_epoch_ms)
SELECT id, 'GOOGLE', google_sub, email, TRUE, created_at_epoch_ms, updated_at_epoch_ms
FROM users WHERE google_sub IS NOT NULL;

INSERT INTO user_identities (user_id, provider, provider_subject, phone_e164, created_at_epoch_ms, last_used_at_epoch_ms)
SELECT id, 'WHATSAPP', phone_e164, phone_e164, created_at_epoch_ms, updated_at_epoch_ms
FROM users WHERE phone_e164 IS NOT NULL;

-- users keeps only verified contact fields. Their UNIQUE constraints carry over with the rename.
ALTER TABLE users DROP CONSTRAINT users_has_identity;
ALTER TABLE users DROP COLUMN google_sub;
ALTER TABLE users DROP COLUMN password_hash;    -- never used: there is no password sign-in
ALTER TABLE users RENAME COLUMN email TO primary_email;
ALTER TABLE users RENAME COLUMN phone_e164 TO primary_phone_e164;
