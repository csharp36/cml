-- Phase 4B: pin flag exempts a branch_index row from TTL cleanup regardless of kind.
ALTER TABLE branch_index
    ADD COLUMN pinned BOOLEAN NOT NULL DEFAULT FALSE;
