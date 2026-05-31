package com.indexer.scip;

public record ScipUploadSession(
        String id,
        int repoId,
        String targetSha,
        String stagingSha,
        String status,
        Integer expectedParts
) {
    public static String stagingKeyFor(String uploadId) {
        return "__staging__:" + uploadId;
    }
}
