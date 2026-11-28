package com.masterenglish.api.modules.content.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.masterenglish.api.TestcontainersConfiguration;
import com.masterenglish.api.common.security.RoleCode;
import com.masterenglish.api.common.storage.MediaStorage;
import com.masterenglish.api.infrastructure.storage.R2MediaStorage;
import com.masterenglish.api.modules.content.dto.request.ReserveMediaUploadRequest;
import com.masterenglish.api.modules.content.dto.response.CompleteMediaResult;
import com.masterenglish.api.modules.content.dto.response.DecodedMediaInfo;
import com.masterenglish.api.modules.content.dto.response.ReserveMediaUploadResult;
import com.masterenglish.api.modules.content.entity.ContentSource;
import com.masterenglish.api.modules.content.entity.MediaUploadGrant;
import com.masterenglish.api.modules.content.repository.ContentSourceRepository;
import com.masterenglish.api.modules.content.repository.MediaAssetRepository;
import com.masterenglish.api.modules.content.repository.MediaUploadGrantRepository;
import com.masterenglish.api.modules.content.service.impl.MediaVerificationServiceImpl;
import com.masterenglish.api.modules.content.service.impl.MediaVerificationTxService;
import com.masterenglish.api.modules.identity.entity.Role;
import com.masterenglish.api.modules.identity.entity.User;
import com.masterenglish.api.modules.identity.repository.RoleRepository;
import com.masterenglish.api.modules.identity.repository.UserRepository;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIf("isDockerAvailable")
class MediaVerificationMinioIntegrationTests {

    private static final String BUCKET = "masterenglish-media-test";
    private static final String ACCESS_KEY = "minioadmin";
    private static final String SECRET_KEY = "minioadmin123";

    @Container
    private static final GenericContainer<?> MINIO =
            new GenericContainer<>(
                            DockerImageName.parse("bitnamilegacy/minio:2025.7.23-debian-12-r3"))
                    .withExposedPorts(9000)
                    .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
                    .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
                    .withEnv("MINIO_DEFAULT_BUCKETS", BUCKET)
                    .waitingFor(
                            Wait.forHttp("/minio/health/ready")
                                    .forPort(9000)
                                    .withStartupTimeout(Duration.ofSeconds(60)));

    private static S3Client s3Client;
    private static MediaStorage minioStorage;

    static boolean isDockerAvailable() {
        try {
            org.testcontainers.DockerClientFactory.instance().client();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @BeforeAll
    static void initMinio() {
        String endpoint = "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
        s3Client =
                S3Client.builder()
                        .endpointOverride(URI.create(endpoint))
                        .region(Region.US_EAST_1)
                        .credentialsProvider(
                                StaticCredentialsProvider.create(
                                        AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                        .serviceConfiguration(
                                S3Configuration.builder()
                                        .pathStyleAccessEnabled(true)
                                        .chunkedEncodingEnabled(false)
                                        .build())
                        .build();
        try {
            s3Client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        } catch (BucketAlreadyExistsException | BucketAlreadyOwnedByYouException ignored) {
        }
        minioStorage = new R2MediaStorage(BUCKET, s3Client);
    }

    @AfterAll
    static void closeMinio() {
        if (s3Client != null) {
            s3Client.close();
        }
    }

    @Autowired private MediaReservationService reservationService;
    @Autowired private MediaUploadGrantRepository grantRepository;
    @Autowired private MediaVerificationTxService txService;
    @Autowired private MediaAssetRepository mediaAssetRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private ContentSourceRepository sourceRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

    private User adminUser;
    private ContentSource activeSource;

    @BeforeEach
    void setUp() {
        transactionTemplate.executeWithoutResult(
                status -> {
                    jdbcTemplate.update("UPDATE media_verification_slots SET worker_token = NULL");
                    jdbcTemplate.update(
                            "UPDATE storage_allocations SET state = 'RELEASED', released_at = NOW() WHERE state = 'RESERVED'");
                    jdbcTemplate.update("DELETE FROM storage_allocations WHERE state = 'RELEASED'");
                    jdbcTemplate.update("DELETE FROM media_upload_requests");
                    jdbcTemplate.update("DELETE FROM media_assets WHERE object_key IS NULL");
                    jdbcTemplate.update("UPDATE storage_pools SET reserved_bytes = 0");

                    Role adminRole =
                            roleRepository.findByCode(RoleCode.CONTENT_ADMIN).orElseThrow();
                    adminUser =
                            new User(
                                    "minio-admin-" + UUID.randomUUID() + "@example.com",
                                    "$2a$12$D4YF1N6gL/x5U12345678u0w7W.zZ6t1m7U9y9X3mN.rLw5QkOe",
                                    "Minio",
                                    "Admin");
                    adminUser.addRole(adminRole);
                    adminUser.markEmailVerified(Instant.now());
                    adminUser = userRepository.saveAndFlush(adminUser);

                    activeSource =
                            sourceRepository.saveAndFlush(
                                    new ContentSource(
                                            UUID.randomUUID(),
                                            "MinIO Source " + UUID.randomUUID(),
                                            "SELF_AUTHORED",
                                            "https://example.com/source",
                                            "MasterEnglish Media Team",
                                            "SELF_AUTHORED",
                                            "https://example.com/license",
                                            "Attribution Note",
                                            "Internal Permission",
                                            "ACTIVE",
                                            adminUser.getId()));
                });
    }

    @Test
    void minioFinalWrite_dbErrorBeforeReady_thenRetryFailureAndSuccess_preservesBytesAndLedger(
            @TempDir Path tempDir) throws Exception {
        byte[] payload = "real-minio-audio-bytes-for-t1-proof".getBytes(StandardCharsets.UTF_8);
        String sha256 =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));

        ReserveMediaUploadResult reserved =
                reservationService.reserveContentUpload(
                        adminUser.getId(),
                        new ReserveMediaUploadRequest(
                                "minio-test.mp3",
                                "audio/mpeg",
                                (long) payload.length,
                                activeSource.getId()));

        // Upload staged object to real MinIO using the exact bucket recorded on the grant
        Path stagedFile = tempDir.resolve("staged.mp3");
        Files.write(stagedFile, payload);
        MediaUploadGrant grant = grantRepository.findById(reserved.uploadRequestId()).orElseThrow();
        try {
            s3Client.createBucket(
                    CreateBucketRequest.builder().bucket(grant.getTempBucket()).build());
        } catch (BucketAlreadyExistsException | BucketAlreadyOwnedByYouException ignored) {
        }
        MediaStorage grantStorage = new R2MediaStorage(grant.getTempBucket(), s3Client);
        grantStorage.put(grant.getTempObjectKey(), stagedFile, "audio/mpeg", sha256);

        // Record staged receipt in DB (do not mutate immutable temp_bucket)
        UUID putToken = UUID.randomUUID();
        transactionTemplate.executeWithoutResult(
                status ->
                        jdbcTemplate.update(
                                "UPDATE media_upload_requests SET put_claim_token = ?, put_claimed_at = NOW(), "
                                        + "staged_size_bytes = ?, staged_sha256 = ?, staged_at = NOW() WHERE id = ?",
                                putToken,
                                (long) payload.length,
                                sha256,
                                reserved.uploadRequestId()));

        String destKey = "content/" + reserved.mediaId() + ".mp3";
        MediaDecoder decoder = mock(MediaDecoder.class);
        when(decoder.decode(any(Path.class), eq("audio/mpeg")))
                .thenReturn(new DecodedMediaInfo("audio/mpeg", "mp3", null, null, 1.5, 44100, 2));

        // 1. First attempt: putIfAbsent writes final object to MinIO, then DB commitSuccess fails
        MediaVerificationTxService failingTx =
                mock(MediaVerificationTxService.class, delegatesTo(txService));
        doThrow(new DataAccessResourceFailureException("Simulated DB outage before READY"))
                .when(failingTx)
                .commitSuccess(any(), any(), any(), any(), any());

        MediaVerificationServiceImpl failingService =
                new MediaVerificationServiceImpl(grantRepository, failingTx, grantStorage, decoder);

        CompleteMediaResult claim1 =
                failingService.completeUpload(adminUser.getId(), reserved.uploadRequestId());
        assertThatThrownBy(
                        () ->
                                failingService.verifyAndFinalize(
                                        reserved.uploadRequestId(), claim1.claimToken()))
                .isInstanceOf(DataAccessResourceFailureException.class);

        // Final bytes exist on MinIO, asset is NOT READY, CONTENT allocation is RESERVED
        assertThat(grantStorage.exists(destKey)).isTrue();
        try (InputStream in = grantStorage.openStream(destKey)) {
            assertThat(in.readAllBytes()).isEqualTo(payload);
        }
        assertThat(mediaAssetRepository.findById(reserved.mediaId()).orElseThrow().getStatus())
                .isEqualTo("PENDING");
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT state FROM storage_allocations WHERE allocation_key = ?",
                                String.class,
                                "req:" + reserved.uploadRequestId() + ":content"))
                .isEqualTo("RESERVED");

        // 2. Second attempt (after lease expiry): storage read of final object fails transiently ->
        // keeps VERIFYING and CONTENT RESERVED without altering final bytes
        transactionTemplate.executeWithoutResult(
                status ->
                        jdbcTemplate.update(
                                "UPDATE media_upload_requests SET lease_until = NOW() - INTERVAL '1 second' WHERE id = ?",
                                reserved.uploadRequestId()));

        MediaStorage transientReadFailStorage = mock(MediaStorage.class, delegatesTo(grantStorage));
        doThrow(new com.masterenglish.api.common.storage.StorageException("Transient read error"))
                .when(transientReadFailStorage)
                .openStream(destKey);

        MediaVerificationServiceImpl retryFailService =
                new MediaVerificationServiceImpl(
                        grantRepository, txService, transientReadFailStorage, decoder);
        CompleteMediaResult claim2 =
                retryFailService.completeUpload(adminUser.getId(), reserved.uploadRequestId());
        assertThatThrownBy(
                        () ->
                                retryFailService.verifyAndFinalize(
                                        reserved.uploadRequestId(), claim2.claimToken()))
                .isInstanceOf(com.masterenglish.api.common.storage.StorageException.class);

        // Final bytes unchanged, CONTENT allocation still RESERVED
        try (InputStream in = grantStorage.openStream(destKey)) {
            assertThat(in.readAllBytes()).isEqualTo(payload);
        }
        assertThat(mediaAssetRepository.findById(reserved.mediaId()).orElseThrow().getStatus())
                .isEqualTo("PENDING");
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT state FROM storage_allocations WHERE allocation_key = ?",
                                String.class,
                                "req:" + reserved.uploadRequestId() + ":content"))
                .isEqualTo("RESERVED");

        // 3. Third attempt: storage and decoder succeed -> COMPLETED & COMMITTED
        transactionTemplate.executeWithoutResult(
                status ->
                        jdbcTemplate.update(
                                "UPDATE media_upload_requests SET lease_until = NOW() - INTERVAL '1 second' WHERE id = ?",
                                reserved.uploadRequestId()));

        MediaVerificationServiceImpl successService =
                new MediaVerificationServiceImpl(grantRepository, txService, grantStorage, decoder);
        CompleteMediaResult claim3 =
                successService.completeUpload(adminUser.getId(), reserved.uploadRequestId());
        CompleteMediaResult done =
                successService.verifyAndFinalize(reserved.uploadRequestId(), claim3.claimToken());

        assertThat(done.status()).isEqualTo("COMPLETED");
        assertThat(mediaAssetRepository.findById(reserved.mediaId()).orElseThrow().getStatus())
                .isEqualTo("READY");
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT state FROM storage_allocations WHERE allocation_key = ?",
                                String.class,
                                "req:" + reserved.uploadRequestId() + ":content"))
                .isEqualTo("COMMITTED");
    }
}
