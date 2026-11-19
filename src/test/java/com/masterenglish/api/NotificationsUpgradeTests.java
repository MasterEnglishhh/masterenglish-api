package com.masterenglish.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

class NotificationsUpgradeTests {

    @Test
    @DisplayName("Nâng cấp CSDL đang ở V9 lên V10 giữ nguyên tài khoản và tạo bảng thông báo")
    void upgradesV9DatabaseToNotifications() throws Exception {
        try (var postgres = new PostgreSQLContainer("postgres:17")) {
            postgres.start();
            var configuration =
                    Flyway.configure()
                            .dataSource(
                                    postgres.getJdbcUrl(),
                                    postgres.getUsername(),
                                    postgres.getPassword())
                            .locations("classpath:db/migration");
            configuration.target("9").load().migrate();

            UUID user = UUID.randomUUID();
            try (var connection =
                            DriverManager.getConnection(
                                    postgres.getJdbcUrl(),
                                    postgres.getUsername(),
                                    postgres.getPassword());
                    var insert =
                            connection.prepareStatement(
                                    "INSERT INTO users(id,email,password_hash,first_name,last_name,status,email_verified_at) VALUES (?,?,'fixture-only','Upgrade','Fixture','ACTIVE',NOW())")) {
                insert.setObject(1, user);
                insert.setString(2, "notify-upgrade@example.test");
                insert.executeUpdate();
            }

            var flyway = configuration.target("latest").load();
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
                                "SELECT count(*) FROM flyway_schema_history WHERE version='10' AND success")) {
                    result.next();
                    assertThat(result.getInt(1)).isEqualTo(1);
                }
                try (var result =
                        statement.executeQuery("SELECT email FROM users WHERE id='" + user + "'")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("notify-upgrade@example.test");
                }
                try (var result =
                        statement.executeQuery(
                                "SELECT minimum_gap_minutes FROM reminder_rules WHERE code='REVIEW_DUE' AND enabled")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getInt(1)).isEqualTo(720);
                }
                statement.executeUpdate(
                        "INSERT INTO notification_preferences(user_id) VALUES ('" + user + "')");
                try (var result =
                        statement.executeQuery(
                                "SELECT email_enabled FROM notification_preferences WHERE user_id='"
                                        + user
                                        + "'")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getBoolean(1)).isFalse();
                }
            }
        }
    }
}
