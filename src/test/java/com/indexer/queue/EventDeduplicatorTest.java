package com.indexer.queue;

import com.indexer.model.IndexingEvent;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class EventDeduplicatorTest {
    @Test
    void collapsesSameRepoEvents() {
        var events = List.of(event(1, "repo-a", "sha1", "sha2"), event(2, "repo-a", "sha2", "sha3"), event(3, "repo-a", "sha3", "sha4"));
        var result = EventDeduplicator.collapse(events);
        assertThat(result.previousSha()).isEqualTo("sha1");
        assertThat(result.currentSha()).isEqualTo("sha4");
        assertThat(result.subsummedEventIds()).containsExactly(2L, 3L);
    }

    @Test
    void singleEventReturnsItself() {
        var events = List.of(event(1, "repo-a", "sha1", "sha2"));
        var result = EventDeduplicator.collapse(events);
        assertThat(result.previousSha()).isEqualTo("sha1");
        assertThat(result.currentSha()).isEqualTo("sha2");
        assertThat(result.subsummedEventIds()).isEmpty();
    }

    @Test
    void handlesNullPreviousSha() {
        var events = List.of(event(1, "repo-a", null, "sha1"), event(2, "repo-a", "sha1", "sha2"));
        var result = EventDeduplicator.collapse(events);
        assertThat(result.previousSha()).isNull();
        assertThat(result.currentSha()).isEqualTo("sha2");
    }

    private IndexingEvent event(long id, String repo, String prev, String curr) {
        return new IndexingEvent(id, repo, "/repos/" + repo, "post-commit", prev, curr, "main", "branch", "pending", null, Instant.now(), null, null, null);
    }
}
