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
 * Upgrades a database that already applied V1..V13 to V14, and builds a fresh database to V14. No
 * Spring context: the test drives Flyway directly against its own PostgreSQL 17 container.
 */
class PracticeMigrationV14Tests {

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
    void v13DataIsDiscardedAndTheAttemptSchemaIsRebuiltByV14() throws Exception {
        flyway("13").clean();
        flyway("13").migrate();

        UUID user = UUID.randomUUID();
        try (Connection c = connect();
                Statement s = c.createStatement()) {
            s.executeUpdate(
                    "INSERT INTO users (id, email, password_hash, display_name, status,"
                            + " email_verified_at, first_name, last_name)"
                            + " VALUES ('"
                            + user
                            + "', 'v13-upgrade@example.com', 'x', 'Learner', 'ACTIVE', now(),"
                            + " 'Upgrade', 'Learner')");
            s.executeUpdate(
                    "INSERT INTO attempts (id, user_id, category, source_type, request_id,"
                            + " request_hash, status, total_count) VALUES ('"
                            + UUID.randomUUID()
                            + "', '"
                            + user
                            + "', 'GRAMMAR', 'MIXED_GRAMMAR', '"
                            + UUID.randomUUID()
                            + "', '"
                            + "a".repeat(64)
                            + "', 'IN_PROGRESS', 2)");
            assertThat(count(c, "attempts")).isEqualTo(1);
        }

        flyway("14").migrate();

        try (Connection c = connect()) {
            assertThat(latestVersion(c)).isEqualTo("14");
            assertThat(count(c, "attempts")).isZero();
            assertThat(columns(c, "attempts"))
                    .contains("deadline_at", "last_active_at", "snapshot_sealed_at", "version")
                    .doesNotContain("question_position");
            assertThat(columns(c, "attempt_answers"))
                    .contains("snapshot_id", "version", "selected_option_id")
                    .doesNotContain("question_position");
            assertThat(
                            singleValue(
                                    c,
                                    "SELECT enabled FROM question_types WHERE code = 'SINGLE_CHOICE'"))
                    .isEqualTo("t");
        }
    }

    @Test
    void freshDatabaseReachesV14WithTheBaselineShells() throws Exception {
        flyway("14").clean();
        flyway("14").migrate();

        try (Connection c = connect()) {
            assertThat(latestVersion(c)).isEqualTo("14");
            for (String shell :
                    new String[] {
                        "question_groups", "practice_sets", "set_items", "attempt_group_snapshots"
                    }) {
                assertThat(count(c, shell)).as(shell).isZero();
            }
            assertThat(
                            singleValue(
                                    c,
                                    "SELECT count(*) FROM pg_constraint"
                                            + " WHERE conname = 'questions_c7'"))
                    .isEqualTo("1");
            assertThat(
                            singleValue(
                                    c,
                                    "SELECT count(*) FROM information_schema.table_constraints"
                                            + " WHERE table_name = 'attempts' AND constraint_type = 'PRIMARY KEY'"))
                    .isEqualTo("1");
        }
    }

    private static String latestVersion(Connection c) throws Exception {
        return singleValue(
                c,
                "SELECT version FROM flyway_schema_history WHERE success"
                        + " ORDER BY installed_rank DESC LIMIT 1");
    }

    private static long count(Connection c, String table) throws Exception {
        return Long.parseLong(singleValue(c, "SELECT count(*) FROM " + table));
    }

    private static java.util.List<String> columns(Connection c, String table) throws Exception {
        java.util.List<String> names = new java.util.ArrayList<>();
        try (Statement s = c.createStatement();
                ResultSet rs =
                        s.executeQuery(
                                "SELECT column_name FROM information_schema.columns"
                                        + " WHERE table_name = '"
                                        + table
                                        + "'")) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return names;
    }

    private static String singleValue(Connection c, String sql) throws Exception {
        try (Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(sql)) {
            assertThat(rs.next()).as(sql).isTrue();
            return rs.getString(1);
        }
    }
}
