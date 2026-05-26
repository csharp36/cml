package com.indexer.db;

import com.indexer.model.IndexingEvent;
import org.jdbi.v3.core.Jdbi;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public class EventDao {

    private final Jdbi jdbi;

    public EventDao(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    public long insert(String repoName, String repoPath, String eventType, String previousSha, String currentSha) {
        return jdbi.withHandle(handle ->
                handle.createUpdate("""
                        INSERT INTO indexing_events (repo_name, repo_path, event_type, previous_sha, current_sha)
                        VALUES (:repoName, :repoPath, :eventType, :previousSha, :currentSha)
                        """)
                        .bind("repoName", repoName)
                        .bind("repoPath", repoPath)
                        .bind("eventType", eventType)
                        .bind("previousSha", previousSha)
                        .bind("currentSha", currentSha)
                        .executeAndReturnGeneratedKeys("id")
                        .mapTo(Long.class)
                        .one()
        );
    }

    public Optional<IndexingEvent> claimNextPending(String workerId) {
        return jdbi.withHandle(handle ->
                handle.createQuery("""
                        UPDATE indexing_events
                        SET status = 'processing', started_at = NOW(), worker_id = :workerId
                        WHERE id = (
                            SELECT id FROM indexing_events
                            WHERE status = 'pending'
                            ORDER BY created_at
                            FOR UPDATE SKIP LOCKED
                            LIMIT 1
                        )
                        RETURNING *
                        """)
                        .bind("workerId", workerId)
                        .map((rs, ctx) -> new IndexingEvent(
                                rs.getLong("id"),
                                rs.getString("repo_name"),
                                rs.getString("repo_path"),
                                rs.getString("event_type"),
                                rs.getString("previous_sha"),
                                rs.getString("current_sha"),
                                rs.getString("status"),
                                rs.getString("error_message"),
                                rs.getTimestamp("created_at") != null
                                        ? rs.getTimestamp("created_at").toInstant()
                                        : null,
                                rs.getTimestamp("started_at") != null
                                        ? rs.getTimestamp("started_at").toInstant()
                                        : null,
                                rs.getTimestamp("completed_at") != null
                                        ? rs.getTimestamp("completed_at").toInstant()
                                        : null,
                                rs.getString("worker_id")
                        ))
                        .findOne()
        );
    }

    public void markCompleted(long eventId) {
        jdbi.useHandle(handle ->
                handle.createUpdate("""
                        UPDATE indexing_events
                        SET status = 'completed', completed_at = NOW()
                        WHERE id = :eventId
                        """)
                        .bind("eventId", eventId)
                        .execute()
        );
    }

    public void markFailed(long eventId, String errorMessage) {
        jdbi.useHandle(handle ->
                handle.createUpdate("""
                        UPDATE indexing_events
                        SET status = 'failed', error_message = :errorMessage, completed_at = NOW()
                        WHERE id = :eventId
                        """)
                        .bind("eventId", eventId)
                        .bind("errorMessage", errorMessage)
                        .execute()
        );
    }

    public List<IndexingEvent> findPendingByRepo(String repoName) {
        return jdbi.withHandle(handle ->
                handle.createQuery("""
                        SELECT * FROM indexing_events
                        WHERE status = 'pending' AND repo_name = :repoName
                        ORDER BY created_at
                        """)
                        .bind("repoName", repoName)
                        .map((rs, ctx) -> new IndexingEvent(
                                rs.getLong("id"),
                                rs.getString("repo_name"),
                                rs.getString("repo_path"),
                                rs.getString("event_type"),
                                rs.getString("previous_sha"),
                                rs.getString("current_sha"),
                                rs.getString("status"),
                                rs.getString("error_message"),
                                rs.getTimestamp("created_at") != null
                                        ? rs.getTimestamp("created_at").toInstant()
                                        : null,
                                rs.getTimestamp("started_at") != null
                                        ? rs.getTimestamp("started_at").toInstant()
                                        : null,
                                rs.getTimestamp("completed_at") != null
                                        ? rs.getTimestamp("completed_at").toInstant()
                                        : null,
                                rs.getString("worker_id")
                        ))
                        .list()
        );
    }

    public int countByStatus(String status) {
        return jdbi.withHandle(handle ->
                handle.createQuery("SELECT COUNT(*) FROM indexing_events WHERE status = :status")
                        .bind("status", status)
                        .mapTo(Integer.class)
                        .one()
        );
    }

    public List<IndexingEvent> findRecentFailed(int limit) {
        return jdbi.withHandle(handle ->
                handle.createQuery("""
                        SELECT * FROM indexing_events
                        WHERE status = 'failed'
                        ORDER BY completed_at DESC
                        LIMIT :limit
                        """)
                        .bind("limit", limit)
                        .map((rs, ctx) -> new IndexingEvent(
                                rs.getLong("id"),
                                rs.getString("repo_name"),
                                rs.getString("repo_path"),
                                rs.getString("event_type"),
                                rs.getString("previous_sha"),
                                rs.getString("current_sha"),
                                rs.getString("status"),
                                rs.getString("error_message"),
                                rs.getTimestamp("created_at") != null
                                        ? rs.getTimestamp("created_at").toInstant()
                                        : null,
                                rs.getTimestamp("started_at") != null
                                        ? rs.getTimestamp("started_at").toInstant()
                                        : null,
                                rs.getTimestamp("completed_at") != null
                                        ? rs.getTimestamp("completed_at").toInstant()
                                        : null,
                                rs.getString("worker_id")
                        ))
                        .list()
        );
    }

    public Optional<IndexingEvent> findById(long id) {
        return jdbi.withHandle(handle ->
                handle.createQuery("SELECT * FROM indexing_events WHERE id = :id")
                        .bind("id", id)
                        .map((rs, ctx) -> new IndexingEvent(
                                rs.getLong("id"),
                                rs.getString("repo_name"),
                                rs.getString("repo_path"),
                                rs.getString("event_type"),
                                rs.getString("previous_sha"),
                                rs.getString("current_sha"),
                                rs.getString("status"),
                                rs.getString("error_message"),
                                rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant() : null,
                                rs.getTimestamp("started_at") != null ? rs.getTimestamp("started_at").toInstant() : null,
                                rs.getTimestamp("completed_at") != null ? rs.getTimestamp("completed_at").toInstant() : null,
                                rs.getString("worker_id")
                        ))
                        .findOne()
        );
    }

    public int resetToPending(long eventId) {
        return jdbi.withHandle(handle ->
                handle.createUpdate("""
                        UPDATE indexing_events
                        SET status = 'pending', error_message = NULL,
                            started_at = NULL, completed_at = NULL, worker_id = NULL
                        WHERE id = :id AND status = 'failed'
                        """)
                        .bind("id", eventId)
                        .execute()
        );
    }

    public void deleteByRepoName(String repoName) {
        jdbi.useHandle(handle ->
                handle.createUpdate("DELETE FROM indexing_events WHERE repo_name = :repoName")
                        .bind("repoName", repoName)
                        .execute()
        );
    }

    public List<IndexingEvent> findFiltered(String repo, String status, Instant since, int limit) {
        return jdbi.withHandle(handle -> {
            var sb = new StringBuilder("SELECT * FROM indexing_events WHERE 1=1");
            if (repo != null) sb.append(" AND repo_name = :repo");
            if (status != null) sb.append(" AND status = :status");
            if (since != null) sb.append(" AND created_at >= :since");
            sb.append(" ORDER BY created_at DESC LIMIT :limit");

            var query = handle.createQuery(sb.toString());
            if (repo != null) query.bind("repo", repo);
            if (status != null) query.bind("status", status);
            if (since != null) query.bind("since", java.sql.Timestamp.from(since));
            query.bind("limit", limit);

            return query.map((rs, ctx) -> new IndexingEvent(
                    rs.getLong("id"),
                    rs.getString("repo_name"),
                    rs.getString("repo_path"),
                    rs.getString("event_type"),
                    rs.getString("previous_sha"),
                    rs.getString("current_sha"),
                    rs.getString("status"),
                    rs.getString("error_message"),
                    rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant() : null,
                    rs.getTimestamp("started_at") != null ? rs.getTimestamp("started_at").toInstant() : null,
                    rs.getTimestamp("completed_at") != null ? rs.getTimestamp("completed_at").toInstant() : null,
                    rs.getString("worker_id")
            )).list();
        });
    }

    public void notifyNewEvent() {
        jdbi.useHandle(handle ->
                handle.execute("NOTIFY new_event")
        );
    }
}
