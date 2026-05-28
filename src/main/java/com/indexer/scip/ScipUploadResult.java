package com.indexer.scip;

public record ScipUploadResult(
        String repo,
        String sha,
        int symbols,
        int relationships,
        int documentsProcessed
) {}
