package com.indexer.model;

import java.time.Instant;

public record BranchIndex(int id, int repoId, String branch, String baseSha, String indexedSha, Instant indexedAt, Instant lastAccessedAt) {}
