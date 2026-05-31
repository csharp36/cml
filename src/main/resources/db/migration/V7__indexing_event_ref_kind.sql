-- Phase 4A: persist the kind of git ref an indexing event targets so the poller
-- can index a tag/SHA correctly instead of hardcoding BRANCH. 'branch' default keeps
-- existing rows and the generic /webhook producer backward-compatible.
ALTER TABLE indexing_events
    ADD COLUMN ref_kind TEXT NOT NULL DEFAULT 'branch';
