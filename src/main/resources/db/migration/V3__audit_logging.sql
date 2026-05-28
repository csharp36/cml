-- Append-only audit trail with SHA-256 hash chain
CREATE TABLE audit_events (
    id              BIGSERIAL PRIMARY KEY,
    timestamp       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    caller_hash     VARCHAR(64) NOT NULL,
    auth_method     VARCHAR(32) NOT NULL,
    transport       VARCHAR(32) NOT NULL,
    source_ip       VARCHAR(45),
    action          VARCHAR(128) NOT NULL,
    repo            VARCHAR(256),
    authorized      BOOLEAN NOT NULL,
    result_status   VARCHAR(16) NOT NULL,
    error_message   TEXT,
    chain_hash      VARCHAR(64) NOT NULL
);

CREATE INDEX idx_audit_events_timestamp ON audit_events (timestamp);
CREATE INDEX idx_audit_events_caller ON audit_events (caller_hash, timestamp);
CREATE INDEX idx_audit_events_repo ON audit_events (repo, timestamp);

-- GDPR erasure target — delete mapping to anonymize, chain stays intact
CREATE TABLE audit_identity_map (
    caller_hash     VARCHAR(64) PRIMARY KEY,
    user_id         VARCHAR(256) NOT NULL,
    display_name    VARCHAR(256),
    first_seen      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_seen       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Single-row serialization point for hash chain
CREATE TABLE audit_chain_state (
    id              INT PRIMARY KEY DEFAULT 1,
    last_hash       VARCHAR(64) NOT NULL,
    last_event_id   BIGINT NOT NULL DEFAULT 0
);

-- Seed the chain with genesis hash (SHA-256 of "genesis")
INSERT INTO audit_chain_state (id, last_hash, last_event_id)
VALUES (1, 'aeebad4a796fcc2e15dc4c6061b45ed9b373f26adfc798ca7d2d8cc58182718e', 0);
