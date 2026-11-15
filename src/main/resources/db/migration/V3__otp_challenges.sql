-- One row per OTP sent. Rows are kept (not deleted) after use so the send history
-- drives per-phone rate limits. login_otps only ever held short-lived codes, so it is dropped.
DROP TABLE login_otps;

CREATE TABLE otp_challenges (
    id                   UUID PRIMARY KEY DEFAULT uuidv7(),
    phone_e164           TEXT NOT NULL,
    purpose              TEXT NOT NULL DEFAULT 'LOGIN' CHECK (purpose IN ('LOGIN', 'LINK')),
    channel              TEXT NOT NULL DEFAULT 'WHATSAPP',
    code_hash            BYTEA NOT NULL,
    attempt_count        INTEGER NOT NULL DEFAULT 0,
    max_attempts         INTEGER NOT NULL,
    expires_at_epoch_ms  BIGINT NOT NULL,
    consumed_at_epoch_ms BIGINT,
    consumed_reason      TEXT CHECK (consumed_reason IN ('VERIFIED', 'MAX_ATTEMPTS', 'SUPERSEDED', 'SEND_FAILED')),
    delivery_channel     TEXT,
    provider_message_id  TEXT,
    created_at_epoch_ms  BIGINT NOT NULL,
    CHECK ((consumed_at_epoch_ms IS NULL) = (consumed_reason IS NULL))
);

-- At most one live (unconsumed) challenge per phone.
CREATE UNIQUE INDEX otp_challenges_live_uq ON otp_challenges (phone_e164) WHERE consumed_at_epoch_ms IS NULL;
-- Window counting for rate limits.
CREATE INDEX otp_challenges_phone_time_idx ON otp_challenges (phone_e164, created_at_epoch_ms);
