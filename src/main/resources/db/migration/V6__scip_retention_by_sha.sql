-- SCIP retention by upload SHA. Today each upload replaces all SCIP for a repo, so
-- only the latest SHA has type resolution. To retain SCIP per tagged build:
--   1. relationships must be attributable to an upload (add upload_sha),
--   2. a symbol must be storable once PER upload, not once per repo.

-- 1. Add upload_sha to relationships; backfill existing rows from the repo's last upload.
ALTER TABLE scip_relationships ADD COLUMN upload_sha VARCHAR(64);
UPDATE scip_relationships sr
   SET upload_sha = r.scip_sha
  FROM repositories r
 WHERE sr.repo_id = r.id AND sr.upload_sha IS NULL;
-- Any rows with no known upload (repo never had scip_sha) get a sentinel so NOT NULL holds.
UPDATE scip_relationships SET upload_sha = 'unknown' WHERE upload_sha IS NULL;
ALTER TABLE scip_relationships ALTER COLUMN upload_sha SET NOT NULL;
CREATE INDEX idx_scip_rel_repo_sha ON scip_relationships (repo_id, upload_sha);

-- 2. Symbols: uniqueness becomes (repo_id, upload_sha, scip_symbol) so multiple SHAs coexist.
ALTER TABLE scip_symbols DROP CONSTRAINT scip_symbols_repo_id_scip_symbol_key;
ALTER TABLE scip_symbols ADD CONSTRAINT scip_symbols_repo_sha_symbol_key
    UNIQUE (repo_id, upload_sha, scip_symbol);
-- No separate index on (repo_id, upload_sha): the unique constraint above covers prefix scans.
