package com.indexer.db;

import com.indexer.model.Repository;
import org.jdbi.v3.core.Jdbi;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public class RepositoryDao {

    private final Jdbi jdbi;

    public RepositoryDao(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    public int insert(Repository repo) {
        return jdbi.withHandle(handle ->
                handle.createUpdate("""
                        INSERT INTO repositories (name, url, branch, clone_path, auth_type, last_indexed_sha, last_indexed_at)
                        VALUES (:name, :url, :branch, :clonePath, :authType, :lastIndexedSha, :lastIndexedAt)
                        """)
                        .bind("name", repo.name())
                        .bind("url", repo.url())
                        .bind("branch", repo.branch())
                        .bind("clonePath", repo.clonePath())
                        .bind("authType", repo.authType())
                        .bind("lastIndexedSha", repo.lastIndexedSha())
                        .bind("lastIndexedAt", repo.lastIndexedAt())
                        .executeAndReturnGeneratedKeys("id")
                        .mapTo(Integer.class)
                        .one()
        );
    }

    public Optional<Repository> findByName(String name) {
        return jdbi.withHandle(handle ->
                handle.createQuery("SELECT * FROM repositories WHERE name = :name")
                        .bind("name", name)
                        .map((rs, ctx) -> new Repository(
                                rs.getInt("id"),
                                rs.getString("name"),
                                rs.getString("url"),
                                rs.getString("branch"),
                                rs.getString("clone_path"),
                                rs.getString("auth_type"),
                                rs.getString("last_indexed_sha"),
                                rs.getTimestamp("last_indexed_at") != null
                                        ? rs.getTimestamp("last_indexed_at").toInstant()
                                        : null
                        ))
                        .findOne()
        );
    }

    public List<Repository> findAll() {
        return jdbi.withHandle(handle ->
                handle.createQuery("SELECT * FROM repositories ORDER BY name")
                        .map((rs, ctx) -> new Repository(
                                rs.getInt("id"),
                                rs.getString("name"),
                                rs.getString("url"),
                                rs.getString("branch"),
                                rs.getString("clone_path"),
                                rs.getString("auth_type"),
                                rs.getString("last_indexed_sha"),
                                rs.getTimestamp("last_indexed_at") != null
                                        ? rs.getTimestamp("last_indexed_at").toInstant()
                                        : null
                        ))
                        .list()
        );
    }

    public void updateLastIndexed(int id, String sha, Instant timestamp) {
        jdbi.useHandle(handle ->
                handle.createUpdate("""
                        UPDATE repositories
                        SET last_indexed_sha = :sha, last_indexed_at = :timestamp
                        WHERE id = :id
                        """)
                        .bind("sha", sha)
                        .bind("timestamp", timestamp)
                        .bind("id", id)
                        .execute()
        );
    }

    public void delete(String name) {
        jdbi.useHandle(handle ->
                handle.createUpdate("DELETE FROM repositories WHERE name = :name")
                        .bind("name", name)
                        .execute()
        );
    }
}
