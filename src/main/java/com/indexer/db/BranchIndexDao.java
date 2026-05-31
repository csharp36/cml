package com.indexer.db;

import com.indexer.model.BranchIndex;
import com.indexer.repository.RefKind;
import org.jdbi.v3.core.Jdbi;

import java.util.List;
import java.util.Optional;

public class BranchIndexDao {

    private final Jdbi jdbi;

    public BranchIndexDao(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    public void upsert(int repoId, String branch, String baseSha, String indexedSha, String refKind) {
        jdbi.useHandle(handle ->
                handle.createUpdate("""
                        INSERT INTO branch_index (repo_id, branch, base_sha, indexed_sha, ref_kind, indexed_at, last_accessed_at)
                        VALUES (:repoId, :branch, :baseSha, :indexedSha, :refKind, NOW(), NOW())
                        ON CONFLICT (repo_id, branch) DO UPDATE
                            SET base_sha = EXCLUDED.base_sha,
                                indexed_sha = EXCLUDED.indexed_sha,
                                ref_kind = EXCLUDED.ref_kind,
                                indexed_at = NOW(),
                                last_accessed_at = NOW()
                        """)
                        .bind("repoId", repoId)
                        .bind("branch", branch)
                        .bind("baseSha", baseSha)
                        .bind("indexedSha", indexedSha)
                        .bind("refKind", refKind)
                        .execute()
        );
    }

    public Optional<BranchIndex> find(int repoId, String branch) {
        return jdbi.withHandle(handle ->
                handle.createQuery("SELECT * FROM branch_index WHERE repo_id = :repoId AND branch = :branch")
                        .bind("repoId", repoId)
                        .bind("branch", branch)
                        .map((rs, ctx) -> new BranchIndex(
                                rs.getInt("id"),
                                rs.getInt("repo_id"),
                                rs.getString("branch"),
                                rs.getString("base_sha"),
                                rs.getString("indexed_sha"),
                                rs.getTimestamp("indexed_at").toInstant(),
                                rs.getTimestamp("last_accessed_at").toInstant(),
                                RefKind.fromDb(rs.getString("ref_kind")),
                                rs.getBoolean("pinned")
                        ))
                        .findOne()
        );
    }

    public void touchLastAccessed(int repoId, String branch) {
        jdbi.useHandle(handle ->
                handle.createUpdate("UPDATE branch_index SET last_accessed_at = NOW() WHERE repo_id = :repoId AND branch = :branch")
                        .bind("repoId", repoId)
                        .bind("branch", branch)
                        .execute()
        );
    }

    public List<BranchIndex> findExpired(int branchTtlDays, int immutableTtlDays) {
        return jdbi.withHandle(handle ->
                handle.createQuery("""
                        SELECT * FROM branch_index
                        WHERE pinned = FALSE
                          AND (
                                (ref_kind = 'branch'       AND last_accessed_at < NOW() - CAST(:branchTtl   || ' days' AS INTERVAL))
                             OR (ref_kind IN ('tag','sha') AND last_accessed_at < NOW() - CAST(:immutableTtl || ' days' AS INTERVAL))
                              )
                        """)
                        .bind("branchTtl", branchTtlDays)
                        .bind("immutableTtl", immutableTtlDays)
                        .map((rs, ctx) -> new BranchIndex(
                                rs.getInt("id"),
                                rs.getInt("repo_id"),
                                rs.getString("branch"),
                                rs.getString("base_sha"),
                                rs.getString("indexed_sha"),
                                rs.getTimestamp("indexed_at").toInstant(),
                                rs.getTimestamp("last_accessed_at").toInstant(),
                                RefKind.fromDb(rs.getString("ref_kind")),
                                rs.getBoolean("pinned")
                        ))
                        .list()
        );
    }

    public int setPinned(int repoId, String branch, boolean pinned) {
        return jdbi.withHandle(handle ->
                handle.createUpdate("UPDATE branch_index SET pinned = :pinned WHERE repo_id = :repoId AND branch = :branch")
                        .bind("pinned", pinned)
                        .bind("repoId", repoId)
                        .bind("branch", branch)
                        .execute()
        );
    }

    public void delete(int repoId, String branch) {
        jdbi.useHandle(handle ->
                handle.createUpdate("DELETE FROM branch_index WHERE repo_id = :repoId AND branch = :branch")
                        .bind("repoId", repoId)
                        .bind("branch", branch)
                        .execute()
        );
    }

    public int countByRepo(int repoId) {
        return jdbi.withHandle(handle ->
                handle.createQuery("SELECT COUNT(*) FROM branch_index WHERE repo_id = :repoId")
                        .bind("repoId", repoId)
                        .mapTo(Integer.class)
                        .one()
        );
    }
}
