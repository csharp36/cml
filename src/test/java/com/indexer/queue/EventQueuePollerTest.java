package com.indexer.queue;

import com.indexer.db.DatabaseManager;
import com.indexer.db.EventDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@Tag("integration")
class EventQueuePollerTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("test_index").withUsername("test").withPassword("test");

    private EventDao eventDao;

    @BeforeEach
    void setUp() {
        var dbManager = new DatabaseManager(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dbManager.initialize();
        eventDao = new EventDao(dbManager.getJdbi());
        dbManager.getJdbi().useHandle(h -> h.execute("DELETE FROM indexing_events"));
    }

    @Test
    void claimsAndProcessesPendingEvent() throws Exception {
        eventDao.insert("repo-a", "/repos/repo-a", "post-commit", "sha1", "sha2", "main");
        var processed = new ArrayList<EventQueuePoller.ProcessableEvent>();
        var latch = new CountDownLatch(1);
        var poller = new EventQueuePoller(eventDao, event -> { processed.add(event); latch.countDown(); }, 100);
        var thread = new Thread(poller);
        thread.start();
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        poller.stop();
        thread.join(2000);
        assertThat(processed).hasSize(1);
        assertThat(processed.get(0).repoName()).isEqualTo("repo-a");
        assertThat(processed.get(0).previousSha()).isEqualTo("sha1");
        assertThat(processed.get(0).currentSha()).isEqualTo("sha2");
        assertThat(eventDao.countByStatus("completed")).isEqualTo(1);
    }

    @Test
    void deduplicatesMultipleEventsForSameRepo() throws Exception {
        eventDao.insert("repo-a", "/repos/repo-a", "post-commit", "sha1", "sha2", "main");
        eventDao.insert("repo-a", "/repos/repo-a", "post-commit", "sha2", "sha3", "main");
        eventDao.insert("repo-a", "/repos/repo-a", "post-commit", "sha3", "sha4", "main");
        var processed = new ArrayList<EventQueuePoller.ProcessableEvent>();
        var latch = new CountDownLatch(1);
        var poller = new EventQueuePoller(eventDao, event -> { processed.add(event); latch.countDown(); }, 100);
        var thread = new Thread(poller);
        thread.start();
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        poller.stop();
        thread.join(2000);
        assertThat(processed).hasSize(1);
        assertThat(processed.get(0).previousSha()).isEqualTo("sha1");
        assertThat(processed.get(0).currentSha()).isEqualTo("sha4");
        assertThat(eventDao.countByStatus("completed")).isEqualTo(3);
    }

    /**
     * Regression test for the cross-ref collapse bug.
     *
     * Before the fix: a branch push event (main, kind=branch) and a tag push event
     * (v1.0, kind=tag) pending simultaneously for the same repo would be collapsed
     * into ONE ProcessableEvent — pairing the primary's (branch, refKind) with the
     * OTHER event's sha.  E.g. branch="v1.0" but currentSha=shaA (main's SHA), or
     * branch="main" but currentSha=shaB (tag's SHA).
     *
     * After the fix: only same-branch + same-refKind events are collapsed together.
     * Events for a different ref remain pending and are claimed in a subsequent poll
     * cycle.  Each ProcessableEvent must have an internally consistent
     * (branch, refKind, currentSha) triple — never crossing ref boundaries.
     */
    @Test
    void doesNotCollapseEventsAcrossDifferentRefs() throws Exception {
        String shaA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String shaB = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

        // Simulate `git push && git push --tags` landing in the queue together
        eventDao.insert("repo-a", "/repos/repo-a", "github_push", "prev0", shaA, "main", "branch");
        eventDao.insert("repo-a", "/repos/repo-a", "github_push", "prev1", shaB, "v1.0", "tag");

        var processed = new ArrayList<EventQueuePoller.ProcessableEvent>();
        // We expect exactly 2 processing calls — one per distinct ref
        var latch = new CountDownLatch(2);
        var poller = new EventQueuePoller(eventDao, event -> {
            synchronized (processed) { processed.add(event); }
            latch.countDown();
        }, 50);

        var thread = new Thread(poller);
        thread.start();
        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        poller.stop();
        thread.join(2000);

        // Both events must have been processed (neither silently dropped)
        assertThat(processed).hasSize(2);
        assertThat(eventDao.countByStatus("completed")).isEqualTo(2);

        // Each ProcessableEvent's (branch, refKind) must be consistent with its sha —
        // i.e. NO cross-ref pairing such as branch="v1.0" with currentSha=shaA.
        for (var e : processed) {
            if ("main".equals(e.branch())) {
                assertThat(e.refKind()).isEqualTo("branch");
                assertThat(e.currentSha())
                        .as("main/branch event must carry main's SHA, not the tag SHA")
                        .isEqualTo(shaA);
            } else if ("v1.0".equals(e.branch())) {
                assertThat(e.refKind()).isEqualTo("tag");
                assertThat(e.currentSha())
                        .as("v1.0/tag event must carry the tag SHA, not main's SHA")
                        .isEqualTo(shaB);
            } else {
                throw new AssertionError("Unexpected branch in processed event: " + e.branch());
            }
        }
    }
}
