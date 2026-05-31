package com.indexer.model;

import com.indexer.repository.RefKind;
import java.time.Instant;

public record BranchIndex(int id, int repoId, String branch, String baseSha, String indexedSha,
                          Instant indexedAt, Instant lastAccessedAt, RefKind refKind, boolean pinned) {}
