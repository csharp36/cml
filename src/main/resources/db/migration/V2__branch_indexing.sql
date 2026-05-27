-- Add branch column to files table
ALTER TABLE files ADD COLUMN branch TEXT NOT NULL DEFAULT 'main';

-- Replace unique constraint to include branch
ALTER TABLE files DROP CONSTRAINT files_repo_id_path_key;
ALTER TABLE files ADD CONSTRAINT files_repo_id_branch_path_key UNIQUE(repo_id, branch, path);

-- Index for branch queries
CREATE INDEX idx_files_branch ON files(repo_id, branch);

-- Add branch column to indexing_events
ALTER TABLE indexing_events ADD COLUMN branch TEXT NOT NULL DEFAULT 'main';

-- Branch index tracking table
CREATE TABLE branch_index (
    id               SERIAL PRIMARY KEY,
    repo_id          INT NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    branch           TEXT NOT NULL,
    base_sha         TEXT NOT NULL,
    indexed_sha      TEXT NOT NULL,
    indexed_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_accessed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(repo_id, branch)
);

CREATE INDEX idx_branch_index_ttl ON branch_index(last_accessed_at);
