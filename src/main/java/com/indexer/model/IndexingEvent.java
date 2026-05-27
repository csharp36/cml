package com.indexer.model;

import java.time.Instant;

public record IndexingEvent(long id, String repoName, String repoPath, String eventType, String previousSha, String currentSha, String branch, String status, String errorMessage, Instant createdAt, Instant startedAt, Instant completedAt, String workerId) {}
