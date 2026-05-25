package com.indexer.model;

import java.time.Instant;

public record Repository(int id, String name, String url, String branch, String clonePath, String authType, String lastIndexedSha, Instant lastIndexedAt) {}
