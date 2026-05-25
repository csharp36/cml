package com.indexer.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DatabaseManager {
    private static final Logger log = LoggerFactory.getLogger(DatabaseManager.class);

    private final HikariDataSource dataSource;
    private final Jdbi jdbi;

    public DatabaseManager(String jdbcUrl, String username, String password) {
        var config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);

        this.dataSource = new HikariDataSource(config);
        this.jdbi = Jdbi.create(dataSource);
    }

    public void initialize() {
        log.info("Running Flyway migrations...");
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        log.info("Migrations complete");
    }

    public Jdbi getJdbi() {
        return jdbi;
    }

    public HikariDataSource getDataSource() {
        return dataSource;
    }

    public void close() {
        dataSource.close();
    }
}
