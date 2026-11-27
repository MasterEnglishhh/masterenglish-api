package com.masterenglish.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.masterenglish.api.modules.content.repository.MediaUploadGrantRepository;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.testcontainers.postgresql.PostgreSQLContainer;

class MediaVerificationProcessTests {

    @Test
    void separateJvmsKeepSlotsAfterConnectionLossAndProcessCrash() throws Exception {
        try (var postgres = new PostgreSQLContainer("postgres:17")) {
            postgres.start();
            Flyway.configure()
                    .dataSource(
                            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();
            String acquireSql =
                    MediaUploadGrantRepository.class
                            .getMethod("acquirePhysicalSlot", UUID.class)
                            .getAnnotation(Query.class)
                            .value()
                            .replace(":token", "?");
            String releaseSql =
                    MediaUploadGrantRepository.class
                            .getMethod("releasePhysicalSlot", UUID.class)
                            .getAnnotation(Query.class)
                            .value()
                            .replace(":token", "?");
            UUID firstToken = UUID.randomUUID();
            UUID secondToken = UUID.randomUUID();
            UUID thirdToken = UUID.randomUUID();
            Process first = null;
            Process second = null;
            try (var connection =
                    DriverManager.getConnection(
                            postgres.getJdbcUrl(),
                            postgres.getUsername(),
                            postgres.getPassword())) {
                first = startWorker(postgres, acquireSql, firstToken);
                second = startWorker(postgres, acquireSql, secondToken);
                var firstOutput = new BufferedReader(new InputStreamReader(first.getInputStream()));
                var secondOutput =
                        new BufferedReader(new InputStreamReader(second.getInputStream()));
                int firstBackendPid = Integer.parseInt(readLine(first, firstOutput));
                int secondBackendPid = Integer.parseInt(readLine(second, secondOutput));
                assertThat(first.pid()).isNotEqualTo(second.pid());
                assertThat(firstBackendPid).isNotEqualTo(secondBackendPid);
                assertThat(updateSlot(connection, acquireSql, thirdToken)).isZero();

                try (var terminate =
                        connection.prepareStatement("SELECT pg_terminate_backend(?)")) {
                    terminate.setInt(1, firstBackendPid);
                    try (var result = terminate.executeQuery()) {
                        assertThat(result.next()).isTrue();
                        assertThat(result.getBoolean(1)).isTrue();
                    }
                }
                var firstInput = new PrintWriter(first.getOutputStream(), true);
                firstInput.println("CHECK_CONNECTION");
                assertThat(readLine(first, firstOutput)).isEqualTo("DISCONNECTED");
                assertThat(first.isAlive()).isTrue();
                assertThat(updateSlot(connection, acquireSql, thirdToken)).isZero();

                first.destroyForcibly();
                assertThat(first.waitFor(10, TimeUnit.SECONDS)).isTrue();
                assertThat(second.isAlive()).isTrue();
                assertThat(updateSlot(connection, acquireSql, thirdToken)).isZero();
                assertThat(updateSlot(connection, releaseSql, thirdToken)).isZero();

                // Test workers have no decoder or storage I/O; exit proves only these JVMs stopped.
                second.destroyForcibly();
                assertThat(second.waitFor(10, TimeUnit.SECONDS)).isTrue();
                assertThat(updateSlot(connection, releaseSql, firstToken)).isEqualTo(1);
                assertThat(updateSlot(connection, acquireSql, thirdToken)).isEqualTo(1);
                assertThat(updateSlot(connection, releaseSql, thirdToken)).isEqualTo(1);
                assertThat(updateSlot(connection, releaseSql, secondToken)).isEqualTo(1);
            } finally {
                stopWorker(first);
                stopWorker(second);
            }
        }
    }

    private static Process startWorker(PostgreSQLContainer postgres, String acquireSql, UUID token)
            throws Exception {
        String classpath =
                Path.of(
                                MediaVerificationProcessTests.class
                                        .getProtectionDomain()
                                        .getCodeSource()
                                        .getLocation()
                                        .toURI())
                        + java.io.File.pathSeparator
                        + Path.of(
                                Class.forName("org.postgresql.Driver")
                                        .getProtectionDomain()
                                        .getCodeSource()
                                        .getLocation()
                                        .toURI());
        var builder =
                new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-Duser.timezone=UTC",
                        "-cp",
                        classpath,
                        SlotWorker.class.getName());
        builder.redirectError(ProcessBuilder.Redirect.INHERIT);
        builder.environment().put("SLOT_TEST_URL", postgres.getJdbcUrl());
        builder.environment().put("SLOT_TEST_USER", postgres.getUsername());
        builder.environment().put("SLOT_TEST_PASSWORD", postgres.getPassword());
        builder.environment().put("SLOT_TEST_SQL", acquireSql);
        builder.environment().put("SLOT_TEST_TOKEN", token.toString());
        return builder.start();
    }

    private static String readLine(Process worker, BufferedReader output) throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(output::readLine);
            try {
                return result.get(15, TimeUnit.SECONDS);
            } finally {
                if (!result.isDone()) {
                    stopWorker(worker);
                }
                result.cancel(true);
                executor.shutdownNow();
            }
        }
    }

    private static int updateSlot(Connection connection, String sql, UUID token) throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, token);
            return statement.executeUpdate();
        }
    }

    private static void stopWorker(Process worker) throws Exception {
        if (worker != null && worker.isAlive()) {
            worker.destroyForcibly();
            assertThat(worker.waitFor(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    public static class SlotWorker {
        public static void main(String[] args) throws Exception {
            try (var connection =
                            DriverManager.getConnection(
                                    System.getenv("SLOT_TEST_URL"),
                                    System.getenv("SLOT_TEST_USER"),
                                    System.getenv("SLOT_TEST_PASSWORD"));
                    var input = new BufferedReader(new InputStreamReader(System.in))) {
                if (updateSlot(
                                connection,
                                System.getenv("SLOT_TEST_SQL"),
                                UUID.fromString(System.getenv("SLOT_TEST_TOKEN")))
                        != 1) {
                    throw new IllegalStateException("No physical slot acquired");
                }
                try (var statement = connection.createStatement();
                        var result = statement.executeQuery("SELECT pg_backend_pid()")) {
                    result.next();
                    System.out.println(result.getInt(1));
                }
                while (input.readLine() != null) {
                    System.out.println(connection.isValid(2) ? "CONNECTED" : "DISCONNECTED");
                }
            }
        }
    }
}
