-- Apple (and later other providers) can tell us a user revoked consent or deleted their account;
-- the sessions that belong to that user are then revoked with this reason.
ALTER TABLE sessions DROP CONSTRAINT sessions_revoked_reason_check;
ALTER TABLE sessions ADD CONSTRAINT sessions_revoked_reason_check
    CHECK (revoked_reason IN ('LOGOUT', 'LOGOUT_ALL', 'REVOKED_BY_USER', 'REUSE_DETECTED', 'PROVIDER_REVOKED'));
