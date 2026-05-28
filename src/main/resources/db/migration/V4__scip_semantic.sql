-- Type-resolved symbol definitions from SCIP
CREATE TABLE scip_symbols (
    id              SERIAL PRIMARY KEY,
    repo_id         INT NOT NULL REFERENCES repositories(id),
    scip_symbol     TEXT NOT NULL,
    display_name    VARCHAR(512),
    kind            VARCHAR(32),
    documentation   TEXT,
    file_path       VARCHAR(1024) NOT NULL,
    start_line      INT,
    end_line        INT,
    upload_sha      VARCHAR(64) NOT NULL,
    uploaded_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (repo_id, scip_symbol)
);

CREATE INDEX idx_scip_symbols_repo_file ON scip_symbols (repo_id, file_path);
CREATE INDEX idx_scip_symbols_repo_name ON scip_symbols (repo_id, display_name);

-- Type-resolved relationships between SCIP symbols
CREATE TABLE scip_relationships (
    id              SERIAL PRIMARY KEY,
    repo_id         INT NOT NULL REFERENCES repositories(id),
    from_symbol     TEXT NOT NULL,
    to_symbol       TEXT NOT NULL,
    kind            VARCHAR(32) NOT NULL,
    file_path       VARCHAR(1024),
    line            INT
);

CREATE INDEX idx_scip_rel_to ON scip_relationships (repo_id, to_symbol, kind);
CREATE INDEX idx_scip_rel_from ON scip_relationships (repo_id, from_symbol, kind);

-- SCIP staleness tracking on repositories
ALTER TABLE repositories ADD COLUMN scip_sha VARCHAR(64);
ALTER TABLE repositories ADD COLUMN scip_uploaded_at TIMESTAMPTZ;
