package com.indexer.webhook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Subset of GitHub's push event payload that CML needs. Unknown fields are ignored.
 * See https://docs.github.com/en/webhooks/webhook-events-and-payloads#push
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubPushPayload(String ref, String before, String after, Repository repository) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Repository(String name) {
    }

    private static final String HEADS_PREFIX = "refs/heads/";

    /** The branch name from {@code ref}, or null if the ref is not a branch. */
    public String branch() {
        return (ref != null && ref.startsWith(HEADS_PREFIX))
                ? ref.substring(HEADS_PREFIX.length())
                : null;
    }

    /** True when this push deletes a branch ({@code after} is the all-zero SHA). */
    public boolean isBranchDeletion() {
        return after != null && !after.isEmpty() && after.chars().allMatch(c -> c == '0');
    }
}
