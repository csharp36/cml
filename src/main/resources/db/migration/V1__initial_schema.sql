-- Repositories tracked by the indexer
CREATE TABLE repositories (
    id              SERIAL PRIMARY KEY,
    name            TEXT UNIQUE NOT NULL,
    url             TEXT NOT NULL,
    branch          TEXT NOT NULL,
    clone_path      TEXT NOT NULL,
    auth_type       TEXT NOT NULL,
    last_indexed_sha TEXT,
    last_indexed_at  TIMESTAMPTZ
);

-- Indexed files
CREATE TABLE files (
    id              SERIAL PRIMARY KEY,
    repo_id         INT NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    path            TEXT NOT NULL,
    language        TEXT,
    size_bytes      INT,
    last_commit_sha TEXT,
    last_modified_at TIMESTAMPTZ,
    UNIQUE(repo_id, path)
);

-- Structural symbols extracted by Tree-sitter
CREATE TABLE symbols (
    id              SERIAL PRIMARY KEY,
    file_id         INT NOT NULL REFERENCES files(id) ON DELETE CASCADE,
    name            TEXT NOT NULL,
    kind            TEXT NOT NULL,
    signature       TEXT,
    start_line      INT NOT NULL,
    end_line        INT NOT NULL,
    parent_id       INT REFERENCES symbols(id) ON DELETE CASCADE,
    visibility      TEXT,
    is_static       BOOLEAN NOT NULL DEFAULT FALSE
);

-- Import statements per file
CREATE TABLE imports (
    id              SERIAL PRIMARY KEY,
    file_id         INT NOT NULL REFERENCES files(id) ON DELETE CASCADE,
    import_path     TEXT NOT NULL,
    alias           TEXT
);

-- Type hierarchy relationships (implements, extends)
CREATE TABLE type_relationships (
    id              SERIAL PRIMARY KEY,
    symbol_id       INT NOT NULL REFERENCES symbols(id) ON DELETE CASCADE,
    related_name    TEXT NOT NULL,
    kind            TEXT NOT NULL
);

-- Full-text searchable file contents
CREATE TABLE file_contents (
    id              SERIAL PRIMARY KEY,
    file_id         INT NOT NULL REFERENCES files(id) ON DELETE CASCADE UNIQUE,
    content         TEXT,
    search_vector   TSVECTOR
);

-- Event queue for git hook notifications
CREATE TABLE indexing_events (
    id              BIGSERIAL PRIMARY KEY,
    repo_name       TEXT NOT NULL,
    repo_path       TEXT NOT NULL,
    event_type      TEXT NOT NULL,
    previous_sha    TEXT,
    current_sha     TEXT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'pending',
    error_message   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    worker_id       TEXT
);

-- Indexes for symbol lookups
CREATE INDEX idx_symbols_name ON symbols(name);
CREATE INDEX idx_symbols_kind ON symbols(kind);
CREATE INDEX idx_symbols_file_id ON symbols(file_id);

-- Indexes for file lookups
CREATE INDEX idx_files_repo_path ON files(repo_id, path);
CREATE INDEX idx_files_language ON files(language);

-- Full-text search index
CREATE INDEX idx_file_contents_search ON file_contents USING GIN(search_vector);

-- Type relationship indexes
CREATE INDEX idx_type_rel_related ON type_relationships(related_name);
CREATE INDEX idx_type_rel_symbol ON type_relationships(symbol_id);

-- Import indexes
CREATE INDEX idx_imports_path ON imports(import_path);

-- Event queue indexes
CREATE INDEX idx_events_pending ON indexing_events(status, created_at) WHERE status = 'pending';
CREATE INDEX idx_events_repo ON indexing_events(repo_name, status);

-- Trigger to auto-update search_vector on content change
CREATE OR REPLACE FUNCTION update_search_vector() RETURNS trigger AS $$
BEGIN
    NEW.search_vector := to_tsvector('english', COALESCE(NEW.content, ''));
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER file_contents_search_update
    BEFORE INSERT OR UPDATE OF content ON file_contents
    FOR EACH ROW EXECUTE FUNCTION update_search_vector();
