package com.masterenglish.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.testcontainers.postgresql.PostgreSQLContainer;

class VocabularyUpgradeTests {
    @Test
    void upgradesAnExistingV5DatabaseWithoutChangingAccountData() throws Exception {
        try (var postgres =
                new PostgreSQLContainer(
                        org.testcontainers.utility.DockerImageName.parse("postgres:17"))) {
            postgres.start();
            Flyway.configure()
                    .dataSource(
                            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration")
                    .target("5")
                    .load()
                    .migrate();
            UUID user = UUID.randomUUID();
            try (var connection =
                    DriverManager.getConnection(
                            postgres.getJdbcUrl(),
                            postgres.getUsername(),
                            postgres.getPassword())) {
                connection.setAutoCommit(false);
                try (var insert =
                        connection.prepareStatement(
                                "INSERT INTO users(id,email,password_hash,first_name,last_name,status,email_verified_at) VALUES (?,?,'fixture-only','Upgrade','Fixture','ACTIVE',NOW())")) {
                    insert.setObject(1, user);
                    insert.setString(2, "upgrade-fixture@example.test");
                    insert.executeUpdate();
                }
                try (var role =
                        connection.prepareStatement(
                                "INSERT INTO user_roles(user_id,role_id) VALUES (?,1)")) {
                    role.setObject(1, user);
                    role.executeUpdate();
                }
                connection.commit();
            }
            var flyway =
                    Flyway.configure()
                            .dataSource(
                                    postgres.getJdbcUrl(),
                                    postgres.getUsername(),
                                    postgres.getPassword())
                            .locations("classpath:db/migration")
                            .load();
            flyway.migrate();
            flyway.validate();
            try (var connection =
                            DriverManager.getConnection(
                                    postgres.getJdbcUrl(),
                                    postgres.getUsername(),
                                    postgres.getPassword());
                    var statement = connection.createStatement()) {
                try (var result =
                        statement.executeQuery(
                                "SELECT email,password_hash FROM users WHERE id='" + user + "'")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("upgrade-fixture@example.test");
                    assertThat(result.getString(2)).isEqualTo("fixture-only");
                }
                try (var result =
                        statement.executeQuery(
                                "SELECT count(*) FROM flyway_schema_history WHERE version IN ('6','7') AND success")) {
                    result.next();
                    assertThat(result.getInt(1)).isEqualTo(2);
                }
                try (var result =
                        statement.executeQuery(
                                "SELECT count(*) FROM user_roles WHERE user_id='"
                                        + user
                                        + "' AND role_id=1")) {
                    result.next();
                    assertThat(result.getInt(1)).isEqualTo(1);
                }
            }
        }
    }

    private enum DatabaseHistory {
        EMPTY,
        MAIN,
        DEV
    }

    @ParameterizedTest
    @EnumSource(DatabaseHistory.class)
    void addsCatalogViewsAndFeatureFlagsFromEachBranchHistory(
            DatabaseHistory history, @TempDir Path devMigrations) throws Exception {
        try (var postgres = new PostgreSQLContainer("postgres:17")) {
            postgres.start();
            var configuration =
                    Flyway.configure()
                            .dataSource(
                                    postgres.getJdbcUrl(),
                                    postgres.getUsername(),
                                    postgres.getPassword());
            if (history == DatabaseHistory.MAIN) {
                configuration.locations("classpath:db/migration").target("8.1").load().migrate();
            } else if (history == DatabaseHistory.DEV) {
                var resources =
                        new PathMatchingResourcePatternResolver()
                                .getResources("classpath*:db/migration/*.sql");
                for (var resource : resources) {
                    if (!"V8_1__add_catalog_read_views.sql".equals(resource.getFilename())) {
                        try (var sql = resource.getInputStream()) {
                            Files.copy(sql, devMigrations.resolve(resource.getFilename()));
                        }
                    }
                }
                configuration
                        .locations("filesystem:" + devMigrations.toString().replace('\\', '/'))
                        .load()
                        .migrate();
                try (var connection =
                                DriverManager.getConnection(
                                        postgres.getJdbcUrl(),
                                        postgres.getUsername(),
                                        postgres.getPassword());
                        var statement = connection.createStatement()) {
                    statement.executeUpdate(
                            "UPDATE feature_flags SET enabled = false WHERE key = 'ai_tutor_chat'");
                }
            }

            var migrations = configuration.locations("classpath:db/migration").target("latest");
            migrations.outOfOrder(history == DatabaseHistory.DEV).load().migrate();
            migrations.outOfOrder(false).load().validate();

            try (var connection =
                            DriverManager.getConnection(
                                    postgres.getJdbcUrl(),
                                    postgres.getUsername(),
                                    postgres.getPassword());
                    var statement = connection.createStatement()) {
                try (var result =
                        statement.executeQuery(
                                """
                        SELECT count(*) FROM pg_views WHERE schemaname = 'public'
                          AND viewname IN ('catalog_visible_items', 'catalog_visible_senses', 'catalog_visible_deck_senses')
                        """)) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getInt(1)).isEqualTo(3);
                }
                try (var result =
                        statement.executeQuery(
                                "SELECT enabled FROM feature_flags WHERE key = 'ai_tutor_chat'")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getBoolean(1)).isEqualTo(history != DatabaseHistory.DEV);
                }
            }
        }
    }
}
