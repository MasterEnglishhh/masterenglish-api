package com.masterenglish.api.modules.content.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.masterenglish.api.infrastructure.storage.R2MediaStorage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

@EnabledIf("isDockerAvailable")
class MediaVerificationDualJvmIntegrationTests {

    private static final String BUCKET = "masterenglish-media-test";
    private static final String MINIO_USER = "minioadmin";
    private static final String MINIO_PASSWORD = "minioadmin123";
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration IPC_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration VERIFY_TIMEOUT = Duration.ofSeconds(25);

    private static final ImageFromDockerfile BACKEND_IMAGE =
            new ImageFromDockerfile("masterenglish-api:m08-runtime-test", false)
                    .withFileFromPath("Dockerfile", Path.of("Dockerfile"))
                    .withFileFromPath("build/libs/app.jar", Path.of("build/libs/app.jar"));

    static boolean isDockerAvailable() {
        try {
            org.testcontainers.DockerClientFactory.instance().client();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Test
    void dualJvm_claimVsPhysicalSlot_sessionLoss_leaseTakeover_andHealthyRelease(
            @TempDir Path tempDir) throws Exception {
        try (DualJvmHarness harness = DualJvmHarness.start(tempDir)) {
            assertThat(harness.worker1.checkStoragePrimary()).isTrue();
            assertThat(harness.worker2.checkStoragePrimary()).isTrue();

            PgSession session1 = harness.findPgSession("m08-worker-1");
            PgSession session2 = harness.findPgSession("m08-worker-2");
            assertThat(session1.pid()).isNotEqualTo(session2.pid());

            GrantFixture g1 = harness.createStagedGrant("audio-1.mp3");
            GrantFixture g2 = harness.createStagedGrant("audio-2.mp3");
            GrantFixture g3 = harness.createStagedGrant("audio-3.mp3");

            harness.worker1.setSlowDecode(true);
            harness.worker2.setSlowDecode(true);

            Map<String, String> c1 =
                    harness.worker1.completeUpload(harness.actorId, g1.requestId());
            assertThat(c1.get("ok")).isEqualTo("true");
            assertThat(c1.get("leaseAcquired")).isEqualTo("true");
            UUID claim1 = UUID.fromString(c1.get("claimToken"));

            Map<String, String> c2 =
                    harness.worker2.completeUpload(harness.actorId, g2.requestId());
            assertThat(c2.get("ok")).isEqualTo("true");
            assertThat(c2.get("leaseAcquired")).isEqualTo("true");
            UUID claim2 = UUID.fromString(c2.get("claimToken"));

            String v1Cmd = harness.worker1.sendVerifyAndFinalize(g1.requestId(), claim1);
            String v2Cmd = harness.worker2.sendVerifyAndFinalize(g2.requestId(), claim2);

            Map<String, String> ffmpeg1 = harness.worker1.awaitEvent("FFMPEG_STARTED", IPC_TIMEOUT);
            Map<String, String> ffmpeg2 = harness.worker2.awaitEvent("FFMPEG_STARTED", IPC_TIMEOUT);
            assertThat(Long.parseLong(ffmpeg1.get("pid"))).isPositive();
            assertThat(Long.parseLong(ffmpeg2.get("pid"))).isPositive();
            assertThat(harness.countOccupiedSlots()).isEqualTo(2);
            harness.assertFfmpegAlive(harness.worker1, ffmpeg1);
            harness.assertFfmpegAlive(harness.worker2, ffmpeg2);

            // 1. Claim capacity blocks third upload while both leases are active
            Map<String, String> c3Blocked =
                    harness.worker1.completeUpload(harness.actorId, g3.requestId());
            assertThat(c3Blocked.get("ok")).isEqualTo("false");
            assertThat(c3Blocked.get("errorCode")).isEqualTo("CONFLICT");

            // 2. Session loss via pg_terminate_backend does NOT release the physical slot
            assertThat(harness.terminatePgBackend(session1.pid())).isTrue();
            assertThat(harness.pgSessionExists(session1.pid(), session1.backendStart())).isFalse();
            assertThat(harness.countOccupiedSlots()).isEqualTo(2);

            // 3. Expire grant 1 lease so grant 3 acquires claim, then physical slot blocks verify
            harness.expireLease(g1.requestId());
            Map<String, String> c3Allowed =
                    harness.worker2.completeUpload(harness.actorId, g3.requestId());
            assertThat(c3Allowed.get("ok")).as("c3Allowed=%s", c3Allowed).isEqualTo("true");
            assertThat(c3Allowed.get("leaseAcquired")).isEqualTo("true");
            UUID claim3 = UUID.fromString(c3Allowed.get("claimToken"));

            String v3BlockedCmd = harness.worker2.sendVerifyAndFinalize(g3.requestId(), claim3);
            Map<String, String> v3Blocked =
                    harness.worker2.awaitVerifyResult(v3BlockedCmd, IPC_TIMEOUT);
            assertThat(v3Blocked.get("ok")).isEqualTo("false");
            assertThat(v3Blocked.get("errorCode")).isEqualTo("CONFLICT");
            assertThat(harness.worker1.countEvents("FFMPEG_STARTED")).isEqualTo(1);
            assertThat(harness.worker2.countEvents("FFMPEG_STARTED")).isEqualTo(1);
            assertThat(harness.worker2.countEvents("STORAGE_PUT_STARTED")).isZero();
            harness.assertFfmpegAlive(harness.worker1, ffmpeg1);
            harness.assertFfmpegAlive(harness.worker2, ffmpeg2);

            // Wait for running workers to finish and release physical slots
            Map<String, String> v1Res = harness.worker1.awaitVerifyResult(v1Cmd, VERIFY_TIMEOUT);
            Map<String, String> v2Res = harness.worker2.awaitVerifyResult(v2Cmd, VERIFY_TIMEOUT);
            assertThat(v1Res.get("ok")).isEqualTo("false");
            assertThat(v1Res.get("errorCode")).isEqualTo("CONFLICT");
            assertThat(v2Res.get("ok")).isEqualTo("true");
            assertThat(v2Res.get("status")).isEqualTo("COMPLETED");
            assertThat(harness.countOccupiedSlots()).isZero();

            // 4. Healthy release allows grant 3 to verify and commit
            harness.worker1.setSlowDecode(false);
            String v3OkCmd = harness.worker1.sendVerifyAndFinalize(g3.requestId(), claim3);
            Map<String, String> v3Ok = harness.worker1.awaitVerifyResult(v3OkCmd, VERIFY_TIMEOUT);
            assertThat(v3Ok.get("ok")).isEqualTo("true");
            assertThat(v3Ok.get("status")).isEqualTo("COMPLETED");
            assertThat(harness.assetStatus(g3.mediaId())).isEqualTo("READY");
            assertThat(harness.readHostBytes("content/" + g3.mediaId() + ".mp3"))
                    .isEqualTo(harness.mp3Payload);
            assertThat(harness.allocationState(g3.requestId(), "content")).isEqualTo("COMMITTED");
            assertThat(harness.countOccupiedSlots()).isZero();

            // 5. Lease takeover fences old claim token
            harness.expireLease(g1.requestId());
            Map<String, String> c1Takeover =
                    harness.worker2.completeUpload(harness.actorId, g1.requestId());
            assertThat(c1Takeover.get("ok")).isEqualTo("true");
            UUID newClaim1 = UUID.fromString(c1Takeover.get("claimToken"));
            assertThat(newClaim1).isNotEqualTo(claim1);

            String staleCmd = harness.worker1.sendVerifyAndFinalize(g1.requestId(), claim1);
            Map<String, String> staleRes = harness.worker1.awaitVerifyResult(staleCmd, IPC_TIMEOUT);
            assertThat(staleRes.get("ok")).isEqualTo("false");
            assertThat(staleRes.get("errorCode")).isEqualTo("CONFLICT");

            String takeoverCmd = harness.worker2.sendVerifyAndFinalize(g1.requestId(), newClaim1);
            Map<String, String> takeoverRes =
                    harness.worker2.awaitVerifyResult(takeoverCmd, VERIFY_TIMEOUT);
            assertThat(takeoverRes.get("ok")).isEqualTo("true");
            assertThat(takeoverRes.get("status")).isEqualTo("COMPLETED");

            // Separate PostgreSQL permission check; not a backend datasource outage proof.
            harness.verifyNonSuperuserDbUnavailable();
        }
    }

    @Test
    void dualJvm_lostResponseAfterMinioPut_andDbCommitFailure_preserveBytesAndLedger(
            @TempDir Path tempDir) throws Exception {
        try (DualJvmHarness harness = DualJvmHarness.start(tempDir)) {
            GrantFixture g1 = harness.createStagedGrant("lost-put.mp3");
            GrantFixture g2 = harness.createStagedGrant("commit-fail.mp3");

            // 1. Lost response after real MinIO putIfAbsent
            String destKey1 = "content/" + g1.mediaId() + ".mp3";
            harness.worker1.setFailAfterPut(destKey1);
            Map<String, String> c1 =
                    harness.worker1.completeUpload(harness.actorId, g1.requestId());
            UUID claim1 = UUID.fromString(c1.get("claimToken"));
            String v1Cmd = harness.worker1.sendVerifyAndFinalize(g1.requestId(), claim1);
            Map<String, String> v1Res = harness.worker1.awaitVerifyResult(v1Cmd, VERIFY_TIMEOUT);
            assertThat(v1Res.get("ok")).isEqualTo("false");
            assertThat(v1Res.get("errorCode")).isEqualTo("StorageException");
            assertThat(harness.worker1.countEvents("STORAGE_PUT_COMPLETED")).isEqualTo(1);

            assertThat(harness.hostStorage.exists(destKey1)).isTrue();
            assertThat(harness.readHostBytes(destKey1)).isEqualTo(harness.mp3Payload);
            assertThat(harness.assetStatus(g1.mediaId())).isEqualTo("PENDING");
            assertThat(harness.grantStatus(g1.requestId())).isEqualTo("VERIFYING");
            assertThat(harness.allocationState(g1.requestId(), "content")).isEqualTo("RESERVED");

            // Retry on worker 2 succeeds via readback without overwriting final bytes
            harness.expireLease(g1.requestId());
            Map<String, String> c1Retry =
                    harness.worker2.completeUpload(harness.actorId, g1.requestId());
            UUID claim1Retry = UUID.fromString(c1Retry.get("claimToken"));
            String v1RetryCmd = harness.worker2.sendVerifyAndFinalize(g1.requestId(), claim1Retry);
            Map<String, String> v1RetryRes =
                    harness.worker2.awaitVerifyResult(v1RetryCmd, VERIFY_TIMEOUT);
            assertThat(v1RetryRes.get("ok")).isEqualTo("true");
            assertThat(v1RetryRes.get("status")).isEqualTo("COMPLETED");
            assertThat(harness.readHostBytes(destKey1)).isEqualTo(harness.mp3Payload);
            assertThat(harness.assetStatus(g1.mediaId())).isEqualTo("READY");
            assertThat(harness.allocationState(g1.requestId(), "content")).isEqualTo("COMMITTED");

            // 2. DB commit failure via temporary trigger on target media asset
            long usedBefore = harness.contentPoolUsedBytes();
            harness.installReadyCommitFailureTrigger(g2.mediaId());
            try {
                Map<String, String> c2 =
                        harness.worker1.completeUpload(harness.actorId, g2.requestId());
                UUID claim2 = UUID.fromString(c2.get("claimToken"));
                String v2Cmd = harness.worker1.sendVerifyAndFinalize(g2.requestId(), claim2);
                Map<String, String> v2Res =
                        harness.worker1.awaitVerifyResult(v2Cmd, VERIFY_TIMEOUT);
                assertThat(v2Res.get("ok")).isEqualTo("false");

                assertThat(harness.grantStatus(g2.requestId())).isEqualTo("VERIFYING");
                assertThat(harness.assetStatus(g2.mediaId())).isEqualTo("PENDING");
                assertThat(harness.allocationState(g2.requestId(), "content"))
                        .isEqualTo("RESERVED");
                assertThat(harness.contentPoolUsedBytes()).isEqualTo(usedBefore);
            } finally {
                harness.removeReadyCommitFailureTrigger();
            }

            harness.expireLease(g2.requestId());
            Map<String, String> c2Retry =
                    harness.worker2.completeUpload(harness.actorId, g2.requestId());
            UUID claim2Retry = UUID.fromString(c2Retry.get("claimToken"));
            String v2RetryCmd = harness.worker2.sendVerifyAndFinalize(g2.requestId(), claim2Retry);
            Map<String, String> v2RetryRes =
                    harness.worker2.awaitVerifyResult(v2RetryCmd, VERIFY_TIMEOUT);
            assertThat(v2RetryRes.get("ok")).isEqualTo("true");
            assertThat(v2RetryRes.get("status")).isEqualTo("COMPLETED");
            assertThat(harness.assetStatus(g2.mediaId())).isEqualTo("READY");
            assertThat(harness.allocationState(g2.requestId(), "content")).isEqualTo("COMMITTED");
            assertThat(harness.contentPoolUsedBytes())
                    .isEqualTo(usedBefore + harness.mp3Payload.length);
        }
    }

    @Test
    void dualJvm_jvmOnlyCrash_andContainerIsolation_keepPhysicalSlotsFailClosed(
            @TempDir Path tempDir) throws Exception {
        try (DualJvmHarness harness = DualJvmHarness.start(tempDir)) {
            GrantFixture g1 = harness.createStagedGrant("crash-1.mp3");
            GrantFixture g2 = harness.createStagedGrant("crash-2.mp3");
            GrantFixture g3 = harness.createStagedGrant("crash-3.mp3");

            harness.worker1.setSlowDecode(true);
            harness.worker2.setSlowDecode(true);

            UUID claim1 =
                    UUID.fromString(
                            harness.worker1
                                    .completeUpload(harness.actorId, g1.requestId())
                                    .get("claimToken"));
            UUID claim2 =
                    UUID.fromString(
                            harness.worker2
                                    .completeUpload(harness.actorId, g2.requestId())
                                    .get("claimToken"));

            harness.worker1.sendVerifyAndFinalize(g1.requestId(), claim1);
            String v2Cmd = harness.worker2.sendVerifyAndFinalize(g2.requestId(), claim2);

            Map<String, String> ffmpeg1 = harness.worker1.awaitEvent("FFMPEG_STARTED", IPC_TIMEOUT);
            harness.worker2.awaitEvent("FFMPEG_STARTED", IPC_TIMEOUT);
            long ffmpegPid1 = Long.parseLong(ffmpeg1.get("pid"));

            // 1. JVM-only crash: kill worker 1 JVM PID while container PID 1 stays alive
            var killRes =
                    harness.worker1.container.execInContainer(
                            "kill", "-9", String.valueOf(harness.worker1.jvmPid));
            assertThat(killRes.getExitCode()).isZero();
            // Confirm JVM process exited inside container while container PID 1 remains alive
            var jvmCheck =
                    harness.worker1.container.execInContainer(
                            "sh",
                            "-c",
                            "i=0; while [ -d /proc/"
                                    + harness.worker1.jvmPid
                                    + " ] && [ $i -lt 50 ]; do sleep 0.1; i=$((i+1)); done; "
                                    + "test ! -d /proc/"
                                    + harness.worker1.jvmPid
                                    + " && echo JVM_EXITED");
            assertThat(jvmCheck.getExitCode()).isZero();
            assertThat(jvmCheck.getStdout().trim()).isEqualTo("JVM_EXITED");
            assertThat(harness.worker1.container.isRunning()).isTrue();
            assertThat(harness.countOccupiedSlots()).isEqualTo(2);
            assertThat(harness.assetStatus(g1.mediaId())).isEqualTo("PENDING");
            assertThat(harness.grantStatus(g1.requestId())).isEqualTo("VERIFYING");
            assertThat(harness.allocationState(g1.requestId(), "content")).isEqualTo("RESERVED");
            assertThat(harness.allocationState(g1.requestId(), "temp")).isEqualTo("RESERVED");
            assertThat(harness.hostStorage.exists("content/" + g1.mediaId() + ".mp3")).isFalse();

            // Observe child ffmpeg state inside container 1 after JVM crash
            var procCheck =
                    harness.worker1.container.execInContainer(
                            "sh",
                            "-c",
                            "test -d /proc/" + ffmpegPid1 + " && echo ALIVE || echo EXITED");
            assertThat(procCheck.getStdout().trim()).isIn("ALIVE", "EXITED");

            // Even after lease expiry allows claim3, physical slot remains fail-closed
            harness.expireLease(g1.requestId());
            Map<String, String> c3 =
                    harness.worker2.completeUpload(harness.actorId, g3.requestId());
            assertThat(c3.get("ok")).isEqualTo("true");
            UUID claim3 = UUID.fromString(c3.get("claimToken"));

            String v3BlockedCmd = harness.worker2.sendVerifyAndFinalize(g3.requestId(), claim3);
            Map<String, String> v3Blocked =
                    harness.worker2.awaitVerifyResult(v3BlockedCmd, IPC_TIMEOUT);
            assertThat(v3Blocked.get("ok")).isEqualTo("false");
            assertThat(v3Blocked.get("errorCode")).isEqualTo("CONFLICT");

            // Wait for healthy worker 2 to complete and release its own slot only
            Map<String, String> v2Res = harness.worker2.awaitVerifyResult(v2Cmd, VERIFY_TIMEOUT);
            assertThat(v2Res.get("ok")).isEqualTo("true");
            assertThat(harness.countOccupiedSlots()).isEqualTo(1);

            // 2. Container isolation: stop container 1; all processes in namespace exit and slot
            // remains occupied
            harness.worker1.container.stop();
            assertThat(harness.worker1.container.isRunning()).isFalse();
            assertThat(harness.worker1.execProcess.waitFor(15, TimeUnit.SECONDS)).isTrue();
            assertThat(harness.countOccupiedSlots()).isEqualTo(1);
        }
    }

    record GrantFixture(UUID requestId, UUID mediaId, String tempBucket, String tempKey) {}

    record PgSession(int pid, String backendStart) {}

    private static final class DualJvmHarness implements AutoCloseable {
        final Network network;
        final PostgreSQLContainer postgres;
        final GenericContainer<?> minio;
        final ServerSocket ipcServer;
        final S3Client hostS3;
        final R2MediaStorage hostStorage;
        final Connection db;
        final UUID actorId;
        final UUID sourceId;
        final byte[] mp3Payload;
        final String mp3Sha256;
        final WorkerHandle worker1;
        final WorkerHandle worker2;

        private DualJvmHarness(
                Network network,
                PostgreSQLContainer postgres,
                GenericContainer<?> minio,
                ServerSocket ipcServer,
                S3Client hostS3,
                R2MediaStorage hostStorage,
                Connection db,
                UUID actorId,
                UUID sourceId,
                byte[] mp3Payload,
                String mp3Sha256,
                WorkerHandle worker1,
                WorkerHandle worker2) {
            this.network = network;
            this.postgres = postgres;
            this.minio = minio;
            this.ipcServer = ipcServer;
            this.hostS3 = hostS3;
            this.hostStorage = hostStorage;
            this.db = db;
            this.actorId = actorId;
            this.sourceId = sourceId;
            this.mp3Payload = mp3Payload;
            this.mp3Sha256 = mp3Sha256;
            this.worker1 = worker1;
            this.worker2 = worker2;
        }

        static DualJvmHarness start(Path tempDir) throws Exception {
            Network network = Network.newNetwork();
            PostgreSQLContainer postgres =
                    new PostgreSQLContainer(DockerImageName.parse("postgres:17"))
                            .withNetwork(network)
                            .withNetworkAliases("pg");
            GenericContainer<?> minio =
                    new GenericContainer<>(
                                    DockerImageName.parse(
                                            "bitnamilegacy/minio:2025.7.23-debian-12-r3"))
                            .withNetwork(network)
                            .withNetworkAliases("minio")
                            .withExposedPorts(9000)
                            .withEnv("MINIO_ROOT_USER", MINIO_USER)
                            .withEnv("MINIO_ROOT_PASSWORD", MINIO_PASSWORD)
                            .withEnv("MINIO_DEFAULT_BUCKETS", BUCKET)
                            .waitingFor(
                                    Wait.forHttp("/minio/health/ready")
                                            .forPort(9000)
                                            .withStartupTimeout(Duration.ofSeconds(60)));

            ServerSocket ipcServer = new ServerSocket(0);
            ipcServer.setSoTimeout((int) STARTUP_TIMEOUT.toMillis());
            Testcontainers.exposeHostPorts(ipcServer.getLocalPort());

            List<AutoCloseable> startupResources = new ArrayList<>();
            startupResources.add(network);
            startupResources.add(postgres::stop);
            startupResources.add(minio::stop);
            startupResources.add(ipcServer);
            try {
                postgres.start();
                minio.start();

                Flyway.configure()
                        .dataSource(
                                postgres.getJdbcUrl(),
                                postgres.getUsername(),
                                postgres.getPassword())
                        .locations("classpath:db/migration")
                        .load()
                        .migrate();

                String hostMinioEndpoint =
                        "http://" + minio.getHost() + ":" + minio.getMappedPort(9000);
                S3Client hostS3 =
                        S3Client.builder()
                                .endpointOverride(java.net.URI.create(hostMinioEndpoint))
                                .region(Region.US_EAST_1)
                                .credentialsProvider(
                                        StaticCredentialsProvider.create(
                                                AwsBasicCredentials.create(
                                                        MINIO_USER, MINIO_PASSWORD)))
                                .serviceConfiguration(
                                        S3Configuration.builder()
                                                .pathStyleAccessEnabled(true)
                                                .chunkedEncodingEnabled(false)
                                                .build())
                                .build();
                try {
                    hostS3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
                } catch (Exception ignored) {
                }
                startupResources.add(hostS3);
                R2MediaStorage hostStorage = new R2MediaStorage(BUCKET, hostS3);

                Connection db =
                        DriverManager.getConnection(
                                postgres.getJdbcUrl(),
                                postgres.getUsername(),
                                postgres.getPassword());
                startupResources.add(db);
                UUID actorId = seedAdminUser(db);
                UUID sourceId = seedContentSource(db, actorId);

                Path testClassesRoot =
                        Path.of(
                                MediaVerificationDualJvmIntegrationTests.class
                                        .getProtectionDomain()
                                        .getCodeSource()
                                        .getLocation()
                                        .toURI());

                WorkerHandle worker1 =
                        startWorkerContainer(
                                "worker-1",
                                "m08-worker-1",
                                network,
                                postgres,
                                ipcServer,
                                testClassesRoot);
                startupResources.add(worker1);
                WorkerHandle worker2 =
                        startWorkerContainer(
                                "worker-2",
                                "m08-worker-2",
                                network,
                                postgres,
                                ipcServer,
                                testClassesRoot);

                startupResources.add(worker2);
                // Generate a valid 4-second MP3 audio fixture using real ffmpeg inside worker1
                // container
                Path hostMp3 = tempDir.resolve("fixture-4s.mp3");
                var genRes =
                        worker1.container.execInContainer(
                                "ffmpeg",
                                "-y",
                                "-f",
                                "lavfi",
                                "-i",
                                "sine=frequency=440:duration=4",
                                "-c:a",
                                "libmp3lame",
                                "-b:a",
                                "64k",
                                "/tmp/fixture-4s.mp3");
                assertThat(genRes.getExitCode()).as(genRes.getStderr()).isZero();
                worker1.container.copyFileFromContainer("/tmp/fixture-4s.mp3", hostMp3.toString());
                byte[] mp3Payload = Files.readAllBytes(hostMp3);
                String mp3Sha256 =
                        HexFormat.of()
                                .formatHex(MessageDigest.getInstance("SHA-256").digest(mp3Payload));

                return new DualJvmHarness(
                        network,
                        postgres,
                        minio,
                        ipcServer,
                        hostS3,
                        hostStorage,
                        db,
                        actorId,
                        sourceId,
                        mp3Payload,
                        mp3Sha256,
                        worker1,
                        worker2);
            } catch (Exception | AssertionError failure) {
                for (int i = startupResources.size() - 1; i >= 0; i--) {
                    try {
                        startupResources.get(i).close();
                    } catch (Throwable cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
                throw failure;
            }
        }

        private static WorkerHandle startWorkerContainer(
                String workerId,
                String pgAppName,
                Network network,
                PostgreSQLContainer postgres,
                ServerSocket ipcServer,
                Path testClassesRoot)
                throws Exception {
            GenericContainer<?> container =
                    new GenericContainer<>(BACKEND_IMAGE)
                            .withNetwork(network)
                            .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("/bin/sh"))
                            .withCommand("-c", "while true; do sleep 1; done");
            Process execProcess = null;
            Socket socket = null;
            try {
                container.start();
                container.copyFileToContainer(
                        MountableFile.forHostPath(testClassesRoot.toString()), "/checks");

                String jdbcUrl =
                        "jdbc:postgresql://pg:5432/"
                                + postgres.getDatabaseName()
                                + "?ApplicationName="
                                + pgAppName;
                ProcessBuilder pb =
                        new ProcessBuilder(
                                "docker",
                                "exec",
                                "-i",
                                "-e",
                                "WORKER_ID=" + workerId,
                                "-e",
                                "WORKER_IPC_HOST=host.testcontainers.internal",
                                "-e",
                                "WORKER_IPC_PORT=" + ipcServer.getLocalPort(),
                                "-e",
                                "WORKER_PG_APP_NAME=" + pgAppName,
                                "-e",
                                "WORKER_MINIO_ENDPOINT=http://minio:9000",
                                "-e",
                                "WORKER_MINIO_BUCKET=" + BUCKET,
                                "-e",
                                "WORKER_MINIO_ACCESS_KEY=" + MINIO_USER,
                                "-e",
                                "WORKER_MINIO_SECRET_KEY=" + MINIO_PASSWORD,
                                container.getContainerId(),
                                "java",
                                "-Xmx384m",
                                "-Xms128m",
                                "-Duser.timezone=UTC",
                                "-Dloader.main=" + MediaVerificationWorkerLauncher.class.getName(),
                                "-Dloader.path=/checks",
                                "-cp",
                                "/app/app.jar",
                                "org.springframework.boot.loader.launch.PropertiesLauncher",
                                "--spring.datasource.url=" + jdbcUrl,
                                "--spring.datasource.username=" + postgres.getUsername(),
                                "--spring.datasource.password=" + postgres.getPassword(),
                                "--app.test.dual-jvm-worker=true",
                                "--app.storage.r2.enabled=false",
                                "--app.security.jwt.secret=test-jwt-secret-must-be-at-least-32-characters-long",
                                "--app.security.rate-limit.secret=test-rate-limit-secret-must-be-at-least-32-chars",
                                "--app.registration.otp-secret=test-otp-secret-must-be-at-least-32-characters",
                                "--server.port=0");
                pb.redirectErrorStream(true);
                pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                execProcess = pb.start();

                socket = ipcServer.accept();
                socket.setSoTimeout(120_000);
                WorkerHandle handle = new WorkerHandle(workerId, container, execProcess, socket);
                handle.awaitReady(STARTUP_TIMEOUT);
                return handle;
            } catch (Exception | AssertionError failure) {
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (Exception cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
                try {
                    container.stop();
                } catch (Exception cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                if (execProcess != null && execProcess.isAlive()) {
                    execProcess.destroyForcibly();
                    if (!execProcess.waitFor(10, TimeUnit.SECONDS)) {
                        failure.addSuppressed(
                                new AssertionError("Worker startup process did not stop"));
                    }
                }
                throw failure;
            }
        }

        GrantFixture createStagedGrant(String fileName) throws Exception {
            Map<String, String> res =
                    worker1.reserveContentUpload(
                            actorId, fileName, "audio/mpeg", mp3Payload.length, sourceId);
            assertThat(res.get("ok")).isEqualTo("true");
            UUID requestId = UUID.fromString(res.get("uploadRequestId"));
            UUID mediaId = UUID.fromString(res.get("mediaId"));
            String tempBucket = res.get("tempBucket");
            String tempKey = res.get("tempObjectKey");

            Path tmpFile = Files.createTempFile("staged-", ".mp3");
            try {
                Files.write(tmpFile, mp3Payload);
                hostStorage.put(tempKey, tmpFile, "audio/mpeg", mp3Sha256);
            } finally {
                Files.deleteIfExists(tmpFile);
            }

            try (PreparedStatement ps =
                    db.prepareStatement(
                            "UPDATE media_upload_requests SET put_claim_token = ?, put_claimed_at = NOW(), "
                                    + "staged_size_bytes = ?, staged_sha256 = ?, staged_at = NOW() WHERE id = ?")) {
                ps.setObject(1, UUID.randomUUID());
                ps.setLong(2, mp3Payload.length);
                ps.setString(3, mp3Sha256);
                ps.setObject(4, requestId);
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }
            return new GrantFixture(requestId, mediaId, tempBucket, tempKey);
        }

        PgSession findPgSession(String appName) throws SQLException {
            try (PreparedStatement ps =
                    db.prepareStatement(
                            "SELECT pid, backend_start::text FROM pg_stat_activity "
                                    + "WHERE application_name = ? AND state IS NOT NULL LIMIT 1")) {
                ps.setString(1, appName);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    return new PgSession(rs.getInt(1), rs.getString(2));
                }
            }
        }

        boolean pgSessionExists(int pid, String backendStart) throws SQLException {
            try (PreparedStatement ps =
                    db.prepareStatement(
                            "SELECT 1 FROM pg_stat_activity WHERE pid = ? AND backend_start::text = ?")) {
                ps.setInt(1, pid);
                ps.setString(2, backendStart);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        }

        boolean terminatePgBackend(int pid) throws SQLException {
            try (PreparedStatement ps = db.prepareStatement("SELECT pg_terminate_backend(?)")) {
                ps.setInt(1, pid);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getBoolean(1);
                }
            }
        }

        void expireLease(UUID requestId) throws SQLException {
            try (PreparedStatement ps =
                    db.prepareStatement(
                            "UPDATE media_upload_requests SET lease_until = NOW() - INTERVAL '1 second' WHERE id = ?")) {
                ps.setObject(1, requestId);
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }
        }

        void assertFfmpegAlive(WorkerHandle worker, Map<String, String> event) throws Exception {
            long pid = Long.parseLong(event.get("pid"));
            assertThat(Long.parseLong(event.get("startMs"))).isPositive();
            var result =
                    worker.container.execInContainer(
                            "sh",
                            "-c",
                            "test \"$(readlink /proc/"
                                    + pid
                                    + "/exe)\" = /usr/bin/ffmpeg && ! grep -q '^State:.*Z' /proc/"
                                    + pid
                                    + "/status");
            assertThat(result.getExitCode()).as("FFmpeg must still be running: %s", event).isZero();
        }

        long countOccupiedSlots() throws SQLException {
            try (var st = db.createStatement();
                    ResultSet rs =
                            st.executeQuery(
                                    "SELECT count(*) FROM media_verification_slots WHERE worker_token IS NOT NULL")) {
                assertThat(rs.next()).isTrue();
                return rs.getLong(1);
            }
        }

        String assetStatus(UUID mediaId) throws SQLException {
            try (PreparedStatement ps =
                    db.prepareStatement("SELECT status FROM media_assets WHERE id = ?")) {
                ps.setObject(1, mediaId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    return rs.getString(1);
                }
            }
        }

        String grantStatus(UUID requestId) throws SQLException {
            try (PreparedStatement ps =
                    db.prepareStatement("SELECT status FROM media_upload_requests WHERE id = ?")) {
                ps.setObject(1, requestId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    return rs.getString(1);
                }
            }
        }

        String allocationState(UUID requestId, String suffix) throws SQLException {
            try (PreparedStatement ps =
                    db.prepareStatement(
                            "SELECT state FROM storage_allocations WHERE allocation_key = ?")) {
                ps.setString(1, "req:" + requestId + ":" + suffix);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    return rs.getString(1);
                }
            }
        }

        long contentPoolUsedBytes() throws SQLException {
            try (PreparedStatement ps =
                    db.prepareStatement(
                            "SELECT used_bytes FROM storage_pools WHERE scope = 'CONTENT'")) {
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    return rs.getLong(1);
                }
            }
        }

        byte[] readHostBytes(String key) throws IOException {
            try (InputStream in = hostStorage.openStream(key)) {
                return in.readAllBytes();
            }
        }

        void installReadyCommitFailureTrigger(UUID targetMediaId) throws SQLException {
            try (var st = db.createStatement()) {
                st.execute(
                        """
                        CREATE OR REPLACE FUNCTION fail_target_media_ready() RETURNS trigger AS $$
                        BEGIN
                            IF NEW.id = '%s'::uuid AND NEW.status = 'READY' THEN
                                RAISE EXCEPTION 'Simulated DB commit failure before READY';
                            END IF;
                            RETURN NEW;
                        END;
                        $$ LANGUAGE plpgsql;
                        """
                                .formatted(targetMediaId));
                st.execute(
                        """
                        DROP TRIGGER IF EXISTS trg_fail_target_media_ready ON media_assets;
                        CREATE TRIGGER trg_fail_target_media_ready
                            BEFORE UPDATE ON media_assets
                            FOR EACH ROW
                            EXECUTE FUNCTION fail_target_media_ready();
                        """);
            }
        }

        void removeReadyCommitFailureTrigger() throws SQLException {
            try (var st = db.createStatement()) {
                st.execute("DROP TRIGGER IF EXISTS trg_fail_target_media_ready ON media_assets;");
                st.execute("DROP FUNCTION IF EXISTS fail_target_media_ready();");
            }
        }

        void verifyNonSuperuserDbUnavailable() throws SQLException {
            String role = "m08_limited_worker";
            String dbName = postgres.getDatabaseName();
            try (var st = db.createStatement()) {
                st.execute(
                        "DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '"
                                + role
                                + "') THEN CREATE ROLE "
                                + role
                                + " LOGIN PASSWORD 'limited_pw'; END IF; END $$;");
                st.execute("GRANT CONNECT ON DATABASE " + dbName + " TO " + role + ";");
            }
            try (Connection limitedConn =
                    DriverManager.getConnection(postgres.getJdbcUrl(), role, "limited_pw")) {
                assertThat(limitedConn.isValid(2)).isTrue();
                try (var st = db.createStatement()) {
                    st.execute("REVOKE CONNECT ON DATABASE " + dbName + " FROM PUBLIC;");
                    st.execute("REVOKE CONNECT ON DATABASE " + dbName + " FROM " + role + ";");
                    st.execute(
                            "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename = '"
                                    + role
                                    + "';");
                }
                org.assertj.core.api.Assertions.assertThatThrownBy(
                                () ->
                                        DriverManager.getConnection(
                                                postgres.getJdbcUrl(), role, "limited_pw"))
                        .isInstanceOf(SQLException.class);
            } finally {
                try (var st = db.createStatement()) {
                    st.execute("GRANT CONNECT ON DATABASE " + dbName + " TO PUBLIC;");
                    st.execute("GRANT CONNECT ON DATABASE " + dbName + " TO " + role + ";");
                }
            }
        }

        private static UUID seedAdminUser(Connection db) throws SQLException {
            short roleId;
            try (PreparedStatement ps =
                    db.prepareStatement("SELECT id FROM roles WHERE code = 'CONTENT_ADMIN'")) {
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    roleId = rs.getShort(1);
                }
            }
            UUID userId = UUID.randomUUID();
            try (PreparedStatement ps =
                    db.prepareStatement(
                            "INSERT INTO users (id, email, password_hash, first_name, last_name, status, email_verified_at, created_at, updated_at) "
                                    + "VALUES (?, ?, ?, 'Dual', 'Worker', 'ACTIVE', NOW(), NOW(), NOW())")) {
                ps.setObject(1, userId);
                ps.setString(2, "dual-admin-" + userId + "@example.com");
                ps.setString(3, "$2a$12$D4YF1N6gL/x5U12345678u0w7W.zZ6t1m7U9y9X3mN.rLw5QkOe");
                ps.executeUpdate();
            }
            try (PreparedStatement ps =
                    db.prepareStatement(
                            "INSERT INTO user_roles (user_id, role_id, assigned_at) VALUES (?, ?, NOW())")) {
                ps.setObject(1, userId);
                ps.setShort(2, roleId);
                ps.executeUpdate();
            }
            return userId;
        }

        private static UUID seedContentSource(Connection db, UUID createdBy) throws SQLException {
            UUID sourceId = UUID.randomUUID();
            try (PreparedStatement ps =
                    db.prepareStatement(
                            "INSERT INTO content_sources (id, title, source_type, source_url, author, license_code, license_url, attribution, permission_note, status, created_by, created_at, updated_at) "
                                    + "VALUES (?, ?, 'SELF_AUTHORED', 'https://example.com/src', 'MasterEnglish', 'SELF_AUTHORED', 'https://example.com/license', 'Attribution Note', 'Internal Permission', 'ACTIVE', ?, NOW(), NOW())")) {
                ps.setObject(1, sourceId);
                ps.setString(2, "DualJVM Source " + sourceId);
                ps.setObject(3, createdBy);
                ps.executeUpdate();
            }
            return sourceId;
        }

        @Override
        public void close() throws Exception {
            List<Throwable> errors = new ArrayList<>();
            closeQuietly(() -> worker1.close(), errors);
            closeQuietly(() -> worker2.close(), errors);
            closeQuietly(db, errors);
            closeQuietly(hostS3, errors);
            closeQuietly(ipcServer, errors);
            closeQuietly(minio::stop, errors);
            closeQuietly(postgres::stop, errors);
            closeQuietly(network::close, errors);
            if (!errors.isEmpty()) {
                AssertionError err = new AssertionError("Harness cleanup failed");
                errors.forEach(err::addSuppressed);
                throw err;
            }
        }

        private static void closeQuietly(AutoCloseable c, List<Throwable> errors) {
            if (c == null) return;
            try {
                c.close();
            } catch (Throwable t) {
                errors.add(t);
            }
        }
    }

    private static final class WorkerHandle implements AutoCloseable {
        final String workerId;
        final GenericContainer<?> container;
        final Process execProcess;
        final Socket socket;
        final PrintWriter out;
        final BufferedReader in;
        final AtomicInteger cmdSeq = new AtomicInteger(1);
        final BlockingQueue<Map<String, String>> events = new LinkedBlockingQueue<>();
        final BlockingQueue<Map<String, String>> responses = new LinkedBlockingQueue<>();
        final Map<String, Integer> eventCounts = new ConcurrentHashMap<>();
        final Thread readerThread;
        volatile long jvmPid;

        WorkerHandle(
                String workerId, GenericContainer<?> container, Process execProcess, Socket socket)
                throws IOException {
            this.workerId = workerId;
            this.container = container;
            this.execProcess = execProcess;
            this.socket = socket;
            this.out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
            this.in =
                    new BufferedReader(
                            new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            this.readerThread = new Thread(this::readLoop, "ipc-reader-" + workerId);
            this.readerThread.setDaemon(true);
            this.readerThread.start();
        }

        private void readLoop() {
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    Map<String, String> parsed = parseLine(line);
                    String kind = parsed.get("_kind");
                    if ("EVENT".equals(kind)) {
                        String type = parsed.getOrDefault("type", "");
                        eventCounts.merge(type, 1, Integer::sum);
                        events.offer(parsed);
                    } else {
                        responses.offer(parsed);
                    }
                }
            } catch (IOException ignored) {
            }
        }

        void awaitReady(Duration timeout) throws Exception {
            Map<String, String> msg =
                    pollMatching(responses, m -> "READY".equals(m.get("_kind")), timeout);
            this.jvmPid = Long.parseLong(msg.get("jvmPid"));
        }

        boolean checkStoragePrimary() throws Exception {
            String cmdId = nextCmdId();
            out.println("CHECK_STORAGE_PRIMARY cmdId=" + cmdId);
            Map<String, String> res = awaitByCmdId("STORAGE_PRIMARY", cmdId, IPC_TIMEOUT);
            return Boolean.parseBoolean(res.get("ok"));
        }

        void setSlowDecode(boolean enabled) throws Exception {
            String cmdId = nextCmdId();
            out.println("SET_SLOW_DECODE cmdId=" + cmdId + " enabled=" + enabled);
            awaitByCmdId("ACK", cmdId, IPC_TIMEOUT);
        }

        void setFailAfterPut(String key) throws Exception {
            String cmdId = nextCmdId();
            out.println("SET_FAIL_AFTER_PUT cmdId=" + cmdId + " key=" + key);
            awaitByCmdId("ACK", cmdId, IPC_TIMEOUT);
        }

        Map<String, String> reserveContentUpload(
                UUID actorId, String fileName, String mimeType, long sizeBytes, UUID sourceId)
                throws Exception {
            String cmdId = nextCmdId();
            out.println(
                    "RESERVE_CONTENT_UPLOAD cmdId="
                            + cmdId
                            + " actorId="
                            + actorId
                            + " fileName="
                            + fileName
                            + " mimeType="
                            + mimeType
                            + " sizeBytes="
                            + sizeBytes
                            + " sourceId="
                            + sourceId);
            return awaitByCmdId("RESERVE_RESULT", cmdId, IPC_TIMEOUT);
        }

        Map<String, String> completeUpload(UUID actorId, UUID requestId) throws Exception {
            String cmdId = nextCmdId();
            out.println(
                    "COMPLETE_UPLOAD cmdId="
                            + cmdId
                            + " actorId="
                            + actorId
                            + " requestId="
                            + requestId);
            return awaitByCmdId("CMD_RESULT", cmdId, IPC_TIMEOUT);
        }

        String sendVerifyAndFinalize(UUID requestId, UUID claimToken) {
            String cmdId = nextCmdId();
            out.println(
                    "VERIFY_AND_FINALIZE cmdId="
                            + cmdId
                            + " requestId="
                            + requestId
                            + " claimToken="
                            + claimToken);
            return cmdId;
        }

        Map<String, String> awaitVerifyResult(String cmdId, Duration timeout) throws Exception {
            return awaitByCmdId("VERIFY_RESULT", cmdId, timeout);
        }

        Map<String, String> awaitEvent(String type, Duration timeout) throws Exception {
            return pollMatching(events, m -> type.equals(m.get("type")), timeout);
        }

        int countEvents(String type) {
            return eventCounts.getOrDefault(type, 0);
        }

        private Map<String, String> awaitByCmdId(String kind, String cmdId, Duration timeout)
                throws Exception {
            return pollMatching(
                    responses,
                    m -> kind.equals(m.get("_kind")) && cmdId.equals(m.get("cmdId")),
                    timeout);
        }

        private Map<String, String> pollMatching(
                BlockingQueue<Map<String, String>> queue,
                Predicate<Map<String, String>> predicate,
                Duration timeout)
                throws Exception {
            long deadline = System.nanoTime() + timeout.toNanos();
            List<Map<String, String>> deferred = new ArrayList<>();
            try {
                while (true) {
                    long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                    if (remainingMs <= 0) {
                        throw new AssertionError(
                                "Timed out waiting for IPC message on " + workerId);
                    }
                    Map<String, String> msg = queue.poll(remainingMs, TimeUnit.MILLISECONDS);
                    if (msg == null) {
                        throw new AssertionError(
                                "Timed out waiting for IPC message on " + workerId);
                    }
                    if (predicate.test(msg)) {
                        return msg;
                    }
                    deferred.add(msg);
                }
            } finally {
                deferred.forEach(queue::offer);
            }
        }

        private String nextCmdId() {
            return workerId + "-" + cmdSeq.getAndIncrement();
        }

        private static Map<String, String> parseLine(String line) {
            Map<String, String> map = new HashMap<>();
            String[] parts = line.trim().split("\\s+");
            if (parts.length == 0) {
                return map;
            }
            map.put("_kind", parts[0]);
            for (int i = 1; i < parts.length; i++) {
                int eq = parts[i].indexOf('=');
                if (eq > 0) {
                    String k = parts[i].substring(0, eq);
                    String v = parts[i].substring(eq + 1);
                    if (k.equals("msg")) {
                        StringBuilder sb = new StringBuilder(v);
                        for (int j = i + 1; j < parts.length; j++) {
                            sb.append(' ').append(parts[j]);
                        }
                        map.put(k, sb.toString());
                        break;
                    }
                    map.put(k, v);
                }
            }
            return map;
        }

        @Override
        public void close() throws Exception {
            try {
                if (socket != null && !socket.isClosed()) {
                    try {
                        out.println("SHUTDOWN cmdId=" + nextCmdId());
                    } catch (Throwable ignored) {
                    }
                    socket.close();
                }
            } finally {
                readerThread.interrupt();
                try {
                    if (execProcess != null && execProcess.isAlive()) {
                        execProcess.destroyForcibly();
                        if (!execProcess.waitFor(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException(
                                    "Worker execProcess still alive for " + workerId);
                        }
                    }
                } finally {
                    if (container != null && container.isRunning()) {
                        container.stop();
                    }
                    readerThread.join(5000);
                    assertThat(readerThread.isAlive()).as("IPC reader must stop").isFalse();
                }
            }
        }
    }
}
