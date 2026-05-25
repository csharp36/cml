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
        eventDao.insert("repo-a", "/repos/repo-a", "post-commit", "sha1", "sha2");
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
        eventDao.insert("repo-a", "/repos/repo-a", "post-commit", "sha1", "sha2");
        eventDao.insert("repo-a", "/repos/repo-a", "post-commit", "sha2", "sha3");
        eventDao.insert("repo-a", "/repos/repo-a", "post-commit", "sha3", "sha4");
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
}
