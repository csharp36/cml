package com.indexer.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubPushPayloadTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesPushPayloadIgnoringUnknownFields() throws Exception {
        String json = """
                {
                  "ref": "refs/heads/main",
                  "before": "aaaa1111",
                  "after": "bbbb2222",
                  "repository": { "name": "cml", "full_name": "csharp36/cml" },
                  "pusher": { "name": "someone" }
                }
                """;
        GitHubPushPayload p = mapper.readValue(json, GitHubPushPayload.class);
        assertThat(p.ref()).isEqualTo("refs/heads/main");
        assertThat(p.before()).isEqualTo("aaaa1111");
        assertThat(p.after()).isEqualTo("bbbb2222");
        assertThat(p.repository().name()).isEqualTo("cml");
    }

    @Test
    void branchExtractsHeadName() {
        var p = new GitHubPushPayload("refs/heads/feature/x", "a", "b", null);
        assertThat(p.branch()).isEqualTo("feature/x");
    }

    @Test
    void branchIsNullForNonBranchRef() {
        var p = new GitHubPushPayload("refs/tags/v1.0", "a", "b", null);
        assertThat(p.branch()).isNull();
    }

    @Test
    void detectsBranchDeletion() {
        var deleted = new GitHubPushPayload("refs/heads/main",
                "abc", "0000000000000000000000000000000000000000", null);
        assertThat(deleted.isBranchDeletion()).isTrue();

        var normal = new GitHubPushPayload("refs/heads/main", "abc", "def123", null);
        assertThat(normal.isBranchDeletion()).isFalse();
    }

    @Test
    void parsesBranchRef() {
        var p = new GitHubPushPayload("refs/heads/main", "a", "b", null);
        assertThat(p.branch()).isEqualTo("main");
        assertThat(p.tag()).isNull();
    }

    @Test
    void parsesTagRef() {
        var p = new GitHubPushPayload("refs/tags/v1.2.3", "a", "b", null);
        assertThat(p.tag()).isEqualTo("v1.2.3");
        assertThat(p.branch()).isNull();
    }

    @Test
    void unknownRefIsNeitherBranchNorTag() {
        var p = new GitHubPushPayload("refs/notes/commits", "a", "b", null);
        assertThat(p.branch()).isNull();
        assertThat(p.tag()).isNull();
    }
}
