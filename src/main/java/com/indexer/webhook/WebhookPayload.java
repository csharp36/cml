package com.indexer.webhook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WebhookPayload(String repoName, String repoPath, String eventType, String previousSha, String currentSha, String timestamp) {
    public boolean isValid() {
        return repoName != null && !repoName.isBlank()
                && repoPath != null && !repoPath.isBlank()
                && eventType != null && !eventType.isBlank()
                && currentSha != null && !currentSha.isBlank();
    }
}
