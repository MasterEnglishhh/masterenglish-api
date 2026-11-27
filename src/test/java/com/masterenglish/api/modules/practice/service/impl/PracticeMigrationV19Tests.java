package com.masterenglish.api.modules.practice.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * V19 on a fresh database and on a database that already applied V18 with attempt data. V19 only
 * adds tables, so the V18 rows must survive it.
 */
class PracticeMigrationV19Tests {

    private static PostgreSQLContainer postgres;

    @BeforeAll
    static void startDatabase() {
        postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:17"));
        postgres.start();
    }

    @AfterAll
    static void stopDatabase() {
        postgres.stop();
    }

    private static Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .target(target)
                .load();
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @Test
    void freshDatabaseReachesV19WithTheMistakeTablesAndTheirChecks() throws Exception {
        flyway("19").clean();
        flyway("19").migrate();
        try (Connection c = connect()) {
            assertThat(latestVersion(c)).isEqualTo("19");
            assertThat(
                            scalar(
                                    c,
                                    "SELECT count(*) FROM pg_constraint WHERE conname = 'mistake_records_c5'"))
                    .isEqualTo("1");
            assertThat(
                            scalar(
                                    c,
                                    "SELECT count(*) FROM pg_indexes WHERE indexname = 'ix_mistakes_user_state'"))
                    .isEqualTo("1");
            assertThat(
                            scalar(
                                    c,
                                    "SELECT count(*) FROM pg_constraint"
                                            + " WHERE conname = 'attempt_source_mistakes_c1' AND contype = 'p'"))
                    .isEqualTo("1");
        }
    }

    @Test
    void upgradeFromV18KeepsTheAttemptRowsItAlreadyHas() throws Exception {
        flyway("18").clean();
        flyway("18").migrate();
        UUID user = UUID.randomUUID();
        UUID attempt = UUID.randomUUID();
        try (Connection c = connect();
                Statement s = c.createStatement()) {
            s.executeUpdate(
                    "INSERT INTO users (id, email, password_hash, display_name, status,"
                            + " email_verified_at, first_name, last_name)"
                            + " VALUES ('"
                            + user
                            + "', 'v15-upgrade@example.com', 'x', 'Learner',"
                            + " 'ACTIVE', now(), 'Upgrade', 'Learner')");
            s.executeUpdate(
                    "INSERT INTO attempts (id, user_id, category, source_type, source_config,"
                            + " request_id, request_hash, status, total_count)"
                            + " VALUES ('"
                            + attempt
                            + "', '"
                            + user
                            + "', 'GRAMMAR', 'MIXED_GRAMMAR',"
                            + " '{}'::jsonb, '"
                            + UUID.randomUUID()
                            + "', '"
                            + "b".repeat(64)
                            + "', 'IN_PROGRESS', 2)");
        }

        flyway("19").migrate();

        try (Connection c = connect()) {
            assertThat(latestVersion(c)).isEqualTo("19");
            assertThat(scalar(c, "SELECT count(*) FROM attempts WHERE id = '" + attempt + "'"))
                    .isEqualTo("1");
            assertThat(scalar(c, "SELECT count(*) FROM mistake_records")).isEqualTo("0");
        }
    }

    private static String latestVersion(Connection c) throws Exception {
        return scalar(
                c,
                "SELECT version FROM flyway_schema_history WHERE success"
                        + " ORDER BY installed_rank DESC LIMIT 1");
    }

    private static String scalar(Connection c, String sql) throws Exception {
        try (Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(sql)) {
            assertThat(rs.next()).as(sql).isTrue();
            return rs.getString(1);
        }
    }
}
