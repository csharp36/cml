-- Record the kind of git ref each branch_index row represents: a moving branch
-- HEAD, an immutable tag, or a bare commit SHA. Enables ref-aware resolution
-- (Phase 1) and future tag pinning / retention (Phase 4). Existing rows are
-- feature branches, so the default backfills them correctly.
ALTER TABLE branch_index
    ADD COLUMN ref_kind TEXT NOT NULL DEFAULT 'branch';
