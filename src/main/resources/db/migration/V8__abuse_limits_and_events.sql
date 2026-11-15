-- Fixed-window counters shared by every server machine (no Redis needed).
-- Keys look like 'otp_send:ip:203.0.113.7' or 'otp_verify_fail:phone:<hmac>'; phones are never stored raw here.
CREATE TABLE rate_limit_buckets (
    bucket_key            TEXT NOT NULL,
    window_start_epoch_ms BIGINT NOT NULL,
    count                 INTEGER NOT NULL,
    PRIMARY KEY (bucket_key, window_start_epoch_ms)
);

-- Append-only audit trail and beta analytics. phone_hash = HMAC(OTP_PEPPER, phone), never the raw number.
CREATE TABLE auth_events (
    id                  UUID PRIMARY KEY DEFAULT uuidv7(),
    event_type          TEXT NOT NULL,
    provider            TEXT,
    outcome             TEXT NOT NULL CHECK (outcome IN ('OK', 'DENIED', 'ERROR')),
    error_code          TEXT,
    user_id             UUID,
    is_beta_flow        BOOLEAN NOT NULL DEFAULT FALSE,
    ip                  TEXT,
    phone_hash          TEXT,
    metadata            JSONB,
    created_at_epoch_ms BIGINT NOT NULL
);
CREATE INDEX auth_events_type_time_idx ON auth_events (event_type, created_at_epoch_ms);

-- Sessions started through a beta flow (WhatsApp OTP) are marked for usage tracking.
ALTER TABLE sessions ADD COLUMN is_beta_flow BOOLEAN NOT NULL DEFAULT FALSE;

-- Delivery status from Meta's webhook: SENT, DELIVERED, READ or FAILED.
ALTER TABLE otp_challenges ADD COLUMN delivery_status TEXT
    CHECK (delivery_status IN ('SENT', 'DELIVERED', 'READ', 'FAILED'));
CREATE INDEX otp_challenges_provider_message_idx ON otp_challenges (provider_message_id) WHERE provider_message_id IS NOT NULL;
