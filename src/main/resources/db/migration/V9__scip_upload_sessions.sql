-- Multi-part SCIP upload sessions. Large SCIP indexes (> 50 MB upload cap) are split
-- client-side into valid sub-indexes and uploaded as parts. Parts are written to
-- scip_symbols/scip_relationships under a synthetic staging upload_sha
-- ('__staging__:<uploadId>') and atomically promoted to the real SHA on completion.

CREATE TABLE scip_upload_sessions (
    id             VARCHAR(64)  PRIMARY KEY,                 -- uploadId (UUID string)
    repo_id        INTEGER      NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    target_sha     VARCHAR(64)  NOT NULL,                    -- the real X-Git-SHA
    staging_sha    VARCHAR(64)  NOT NULL,                    -- '__staging__:<uploadId>'
    status         VARCHAR(16)  NOT NULL DEFAULT 'open',     -- open | completed | aborted
    expected_parts INTEGER,                                  -- nullable; from X-Scip-Parts
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Reaper scans open sessions by age.
CREATE INDEX idx_scip_sessions_status_updated ON scip_upload_sessions (status, updated_at);

-- Idempotency ledger: a part recorded here has already been inserted. Re-uploading
-- a recorded part is a no-op (counts returned from this row).
CREATE TABLE scip_upload_parts (
    session_id   VARCHAR(64) NOT NULL REFERENCES scip_upload_sessions(id) ON DELETE CASCADE,
    part_number  INTEGER     NOT NULL,
    byte_size    BIGINT      NOT NULL,
    symbol_count INTEGER     NOT NULL,
    rel_count    INTEGER     NOT NULL,
    received_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (session_id, part_number)
);
