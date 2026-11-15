ALTER TABLE users
    ALTER COLUMN email DROP NOT NULL,
    ALTER COLUMN password_hash DROP NOT NULL;

ALTER TABLE users
    ADD COLUMN phone_e164 TEXT UNIQUE,
    ADD COLUMN google_sub TEXT UNIQUE;

ALTER TABLE users
    ADD CONSTRAINT users_has_identity CHECK (
        email IS NOT NULL OR phone_e164 IS NOT NULL OR google_sub IS NOT NULL
    );

CREATE TABLE login_otps (
    phone_e164 TEXT PRIMARY KEY,
    otp_hash TEXT NOT NULL,
    expires_at_epoch_ms BIGINT NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    created_at_epoch_ms BIGINT NOT NULL
);