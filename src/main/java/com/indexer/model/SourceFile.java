package com.indexer.model;

import java.time.Instant;

public record SourceFile(int id, int repoId, String path, String language, int sizeBytes, String lastCommitSha, Instant lastModifiedAt) {}
