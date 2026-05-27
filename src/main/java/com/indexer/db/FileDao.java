package com.indexer.db;

import com.indexer.model.SourceFile;
import org.jdbi.v3.core.Jdbi;

import java.util.List;
import java.util.Optional;

public class FileDao {

    private final Jdbi jdbi;

    public FileDao(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    public int upsert(SourceFile file) {
        return jdbi.withHandle(handle ->
                handle.createUpdate("""
                        INSERT INTO files (repo_id, branch, path, language, size_bytes, last_commit_sha, last_modified_at)
                        VALUES (:repoId, :branch, :path, :language, :sizeBytes, :lastCommitSha, :lastModifiedAt)
                        ON CONFLICT (repo_id, branch, path) DO UPDATE
                            SET language        = EXCLUDED.language,
                                size_bytes      = EXCLUDED.size_bytes,
                                last_commit_sha = EXCLUDED.last_commit_sha,
                                last_modified_at = EXCLUDED.last_modified_at
                        """)
                        .bind("repoId", file.repoId())
                        .bind("branch", file.branch())
                        .bind("path", file.path())
                        .bind("language", file.language())
                        .bind("sizeBytes", file.sizeBytes())
                        .bind("lastCommitSha", file.lastCommitSha())
                        .bind("lastModifiedAt", file.lastModifiedAt())
                        .executeAndReturnGeneratedKeys("id")
                        .mapTo(Integer.class)
                        .one()
        );
    }

    public Optional<SourceFile> findByRepoAndPath(int repoId, String path) {
        return jdbi.withHandle(handle ->
                handle.createQuery("SELECT * FROM files WHERE repo_id = :repoId AND path = :path ORDER BY id LIMIT 1")
                        .bind("repoId", repoId)
                        .bind("path", path)
                        .map((rs, ctx) -> new SourceFile(
                                rs.getInt("id"),
                                rs.getInt("repo_id"),
                                rs.getString("branch"),
                                rs.getString("path"),
                                rs.getString("language"),
                                rs.getInt("size_bytes"),
                                rs.getString("last_commit_sha"),
                                rs.getTimestamp("last_modified_at") != null
                                        ? rs.getTimestamp("last_modified_at").toInstant()
                                        : null
                        ))
                        .findOne()
        );
    }

    public void deleteByRepoAndPath(int repoId, String path) {
        jdbi.useHandle(handle ->
                handle.createUpdate("DELETE FROM files WHERE repo_id = :repoId AND path = :path")
                        .bind("repoId", repoId)
                        .bind("path", path)
                        .execute()
        );
    }

    public void deleteByRepoAndBranch(int repoId, String branch) {
        jdbi.useHandle(handle ->
                handle.createUpdate("DELETE FROM files WHERE repo_id = :repoId AND branch = :branch")
                        .bind("repoId", repoId)
                        .bind("branch", branch)
                        .execute()
        );
    }

    public int countByRepo(int repoId) {
        return jdbi.withHandle(handle ->
                handle.createQuery("SELECT COUNT(*) FROM files WHERE repo_id = :repoId")
                        .bind("repoId", repoId)
                        .mapTo(Integer.class)
                        .one()
        );
    }

    public void deleteByRepoId(int repoId) {
        jdbi.useHandle(handle ->
                handle.createUpdate("DELETE FROM files WHERE repo_id = :repoId")
                        .bind("repoId", repoId)
                        .execute()
        );
    }

    public List<SourceFile> findByRepo(int repoId) {
        return jdbi.withHandle(handle ->
                handle.createQuery("SELECT * FROM files WHERE repo_id = :repoId")
                        .bind("repoId", repoId)
                        .map((rs, ctx) -> new SourceFile(
                                rs.getInt("id"),
                                rs.getInt("repo_id"),
                                rs.getString("branch"),
                                rs.getString("path"),
                                rs.getString("language"),
                                rs.getInt("size_bytes"),
                                rs.getString("last_commit_sha"),
                                rs.getTimestamp("last_modified_at") != null
                                        ? rs.getTimestamp("last_modified_at").toInstant()
                                        : null
                        ))
                        .list()
        );
    }
}
