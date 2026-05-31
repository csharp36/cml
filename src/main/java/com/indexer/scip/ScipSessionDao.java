package com.indexer.scip;

import org.jdbi.v3.core.Jdbi;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Data-access for multi-part SCIP upload sessions and their part ledger. */
public class ScipSessionDao {

    private final Jdbi jdbi;

    public ScipSessionDao(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    public ScipUploadSession create(int repoId, String targetSha) {
        String id = UUID.randomUUID().toString();
        String stagingSha = ScipUploadSession.stagingKeyFor(id);
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO scip_upload_sessions (id, repo_id, target_sha, staging_sha, status)
                VALUES (:id, :repoId, :targetSha, :stagingSha, 'open')
                """)
                .bind("id", id).bind("repoId", repoId)
                .bind("targetSha", targetSha).bind("stagingSha", stagingSha)
                .execute());
        return new ScipUploadSession(id, repoId, targetSha, stagingSha, "open", null);
    }

    public Optional<ScipUploadSession> find(String id) {
        return jdbi.withHandle(h -> h.createQuery("""
                SELECT id, repo_id, target_sha, staging_sha, status, expected_parts
                FROM scip_upload_sessions WHERE id = :id
                """)
                .bind("id", id)
                .map((rs, ctx) -> new ScipUploadSession(
                        rs.getString("id"), rs.getInt("repo_id"), rs.getString("target_sha"),
                        rs.getString("staging_sha"), rs.getString("status"),
                        (Integer) rs.getObject("expected_parts")))
                .findOne());
    }

    public boolean partExists(String sessionId, int partNumber) {
        return jdbi.withHandle(h -> h.createQuery(
                "SELECT count(*) > 0 FROM scip_upload_parts WHERE session_id = :s AND part_number = :p")
                .bind("s", sessionId).bind("p", partNumber)
                .mapTo(Boolean.class).one());
    }

    /** @return [symbolCount, relCount] for a recorded part, or empty if not recorded. */
    public Optional<int[]> partCounts(String sessionId, int partNumber) {
        return jdbi.withHandle(h -> h.createQuery(
                "SELECT symbol_count, rel_count FROM scip_upload_parts WHERE session_id = :s AND part_number = :p")
                .bind("s", sessionId).bind("p", partNumber)
                .map((rs, ctx) -> new int[]{rs.getInt("symbol_count"), rs.getInt("rel_count")})
                .findOne());
    }

    public void recordPart(String sessionId, int partNumber, long byteSize, int symbolCount, int relCount) {
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO scip_upload_parts (session_id, part_number, byte_size, symbol_count, rel_count)
                VALUES (:s, :p, :bytes, :syms, :rels)
                """)
                .bind("s", sessionId).bind("p", partNumber).bind("bytes", byteSize)
                .bind("syms", symbolCount).bind("rels", relCount)
                .execute());
    }

    public void markStatus(String sessionId, String status) {
        jdbi.useHandle(h -> h.createUpdate(
                "UPDATE scip_upload_sessions SET status = :st, updated_at = NOW() WHERE id = :id")
                .bind("st", status).bind("id", sessionId).execute());
    }

    public List<ScipUploadSession> findOpenOlderThan(int ttlHours) {
        return jdbi.withHandle(h -> h.createQuery("""
                SELECT id, repo_id, target_sha, staging_sha, status, expected_parts
                FROM scip_upload_sessions
                WHERE status = 'open' AND updated_at < NOW() - CAST(:hours || ' hours' AS INTERVAL)
                """)
                .bind("hours", ttlHours)
                .map((rs, ctx) -> new ScipUploadSession(
                        rs.getString("id"), rs.getInt("repo_id"), rs.getString("target_sha"),
                        rs.getString("staging_sha"), rs.getString("status"),
                        (Integer) rs.getObject("expected_parts")))
                .list());
    }

    /** Delete a session, its part ledger (FK cascade), and any staging rows it left behind. */
    public void deleteSessionAndStaging(ScipUploadSession session) {
        jdbi.useTransaction(h -> {
            h.createUpdate("DELETE FROM scip_relationships WHERE repo_id = :r AND upload_sha = :sha")
                    .bind("r", session.repoId()).bind("sha", session.stagingSha()).execute();
            h.createUpdate("DELETE FROM scip_symbols WHERE repo_id = :r AND upload_sha = :sha")
                    .bind("r", session.repoId()).bind("sha", session.stagingSha()).execute();
            h.createUpdate("DELETE FROM scip_upload_sessions WHERE id = :id")
                    .bind("id", session.id()).execute();
        });
    }
}
