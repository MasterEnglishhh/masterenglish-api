package com.masterenglish.api.modules.content.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.masterenglish.api.TestcontainersConfiguration;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.common.security.RoleCode;
import com.masterenglish.api.common.storage.MediaStorage;
import com.masterenglish.api.common.storage.StorageDisabledException;
import com.masterenglish.api.common.storage.StorageException;
import com.masterenglish.api.modules.content.dto.request.ReserveMediaUploadRequest;
import com.masterenglish.api.modules.content.dto.response.ReserveMediaUploadResult;
import com.masterenglish.api.modules.content.dto.response.StageMediaResult;
import com.masterenglish.api.modules.content.entity.ContentSource;
import com.masterenglish.api.modules.content.entity.MediaUploadGrant;
import com.masterenglish.api.modules.content.repository.ContentSourceRepository;
import com.masterenglish.api.modules.content.repository.MediaAssetRepository;
import com.masterenglish.api.modules.content.repository.MediaUploadGrantRepository;
import com.masterenglish.api.modules.identity.entity.Role;
import com.masterenglish.api.modules.identity.entity.User;
import com.masterenglish.api.modules.identity.repository.RoleRepository;
import com.masterenglish.api.modules.identity.repository.UserRepository;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Import({TestcontainersConfiguration.class, MediaStagingIntegrationTests.ClockFixture.class})
@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIf("isDockerAvailable")
class MediaStagingIntegrationTests {

    static boolean isDockerAvailable() {
        try {
            org.testcontainers.DockerClientFactory.instance().client();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Autowired private MediaReservationService reservationService;
    @Autowired private MediaStagingService stagingService;
    @Autowired private MutableClock clock;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private ContentSourceRepository sourceRepository;
    @Autowired private MediaAssetRepository mediaAssetRepository;
    @Autowired private MediaUploadGrantRepository uploadGrantRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

    @MockitoBean private MediaStorage mediaStorage;

    private User contentAdminUser;
    private User secondAdminUser;
    private User learnerUser;
    private ContentSource activeSource;
    private final Map<String, byte[]> storedObjects = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        clock.reset();
        reset(mediaStorage);
        storedObjects.clear();

        when(mediaStorage.isAvailable()).thenReturn(true);
        when(mediaStorage.bucket()).thenReturn("test-media-bucket");

        doAnswer(
                        invocation -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .as("Storage put must occur outside any active DB transaction")
                                    .isFalse();
                            String key = invocation.getArgument(0);
                            Path file = invocation.getArgument(1);
                            storedObjects.put(key, Files.readAllBytes(file));
                            return null;
                        })
                .when(mediaStorage)
                .put(anyString(), any(Path.class), anyString(), anyString());

        when(mediaStorage.exists(anyString()))
                .thenAnswer(invocation -> storedObjects.containsKey(invocation.getArgument(0)));

        cleanUpTables();

        transactionTemplate.execute(
                status -> {
                    Role adminRole =
                            roleRepository.findByCode(RoleCode.CONTENT_ADMIN).orElseThrow();
                    Role learnerRole = roleRepository.findByCode(RoleCode.LEARNER).orElseThrow();

                    contentAdminUser =
                            userRepository.save(
                                    new User(
                                            "admin-staging-" + UUID.randomUUID() + "@example.com",
                                            "$2a$12$D4YF1N6gL/x5U12345678u0w7W.zZ6t1m7U9y9X3mN.rLw5QkOe",
                                            "ContentAdmin",
                                            "Staging"));
                    contentAdminUser.addRole(adminRole);
                    contentAdminUser.markEmailVerified(Instant.now());
                    contentAdminUser = userRepository.saveAndFlush(contentAdminUser);

                    secondAdminUser =
                            userRepository.save(
                                    new User(
                                            "admin2-staging-" + UUID.randomUUID() + "@example.com",
                                            "$2a$12$D4YF1N6gL/x5U12345678u0w7W.zZ6t1m7U9y9X3mN.rLw5QkOe",
                                            "AdminTwo",
                                            "Staging"));
                    secondAdminUser.addRole(adminRole);
                    secondAdminUser.markEmailVerified(Instant.now());
                    secondAdminUser = userRepository.saveAndFlush(secondAdminUser);

                    learnerUser =
                            userRepository.save(
                                    new User(
                                            "learner-staging-" + UUID.randomUUID() + "@example.com",
                                            "$2a$12$D4YF1N6gL/x5U12345678u0w7W.zZ6t1m7U9y9X3mN.rLw5QkOe",
                                            "Learner",
                                            "Staging"));
                    learnerUser.addRole(learnerRole);
                    learnerUser.markEmailVerified(Instant.now());
                    learnerUser = userRepository.saveAndFlush(learnerUser);

                    activeSource =
                            sourceRepository.saveAndFlush(
                                    new ContentSource(
                                            UUID.randomUUID(),
                                            "Internal Media Staging Source",
                                            "SELF_AUTHORED",
                                            "https://example.com/source",
                                            "MasterEnglish Media Team",
                                            "SELF_AUTHORED",
                                            "https://example.com/license",
                                            "Attribution Note",
                                            "Internal Permission",
                                            "ACTIVE",
                                            contentAdminUser.getId()));
                    return null;
                });
    }

    @AfterEach
    void tearDown() {
        clock.reset();
        cleanUpTables();
    }

    private void cleanUpTables() {
        transactionTemplate.execute(
                status -> {
                    jdbcTemplate.update(
                            "UPDATE storage_allocations SET state = 'RELEASED', released_at = NOW() WHERE state = 'RESERVED'");
                    jdbcTemplate.update("DELETE FROM storage_allocations WHERE state = 'RELEASED'");
                    jdbcTemplate.update("DELETE FROM media_upload_requests");
                    jdbcTemplate.update("DELETE FROM media_assets WHERE object_key IS NULL");
                    jdbcTemplate.update("UPDATE storage_pools SET reserved_bytes = 0");
                    return null;
                });
    }

    @Test
    @DisplayName("Valid audio upload stages file outside transaction and records staging receipt")
    void stage_validAudioStream_stagesObjectAndRecordsReceipt() throws Exception {
        byte[] payload =
                "synthetic-audio-data-at-least-100-bytes-for-testing-1234567890abcdefghijklmnopqrstuvwxyz"
                        .getBytes(StandardCharsets.UTF_8);
        long size = payload.length;

        ReserveMediaUploadResult reserved =
                reservationService.reserveContentUpload(
                        contentAdminUser.getId(),
                        new ReserveMediaUploadRequest(
                                "lesson.mp3", "audio/mpeg", size, activeSource.getId()));

        ByteArrayInputStream stream = new ByteArrayInputStream(payload);
        StageMediaResult result =
                stagingService.stageMediaUpload(
                        contentAdminUser.getId(), reserved.uploadRequestId(), stream, "audio/mpeg");

        assertThat(result.uploadRequestId()).isEqualTo(reserved.uploadRequestId());
        assertThat(result.mediaId()).isEqualTo(reserved.mediaId());
        assertThat(result.sizeBytes()).isEqualTo(size);
        assertThat(result.status()).isEqualTo("PENDING");

        String expectedSha256 =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        assertThat(result.sha256Checksum()).isEqualTo(expectedSha256);

        MediaUploadGrant grant =
                uploadGrantRepository.findById(reserved.uploadRequestId()).orElseThrow();
        assertThat(grant.getPutClaimToken()).isNotNull();
        assertThat(grant.getPutClaimedAt()).isNotNull();
        assertThat(grant.getStagedSizeBytes()).isEqualTo(size);
        assertThat(grant.getStagedSha256()).isEqualTo(expectedSha256);
        assertThat(grant.getStagedAt()).isNotNull();
        assertThat(grant.getStatus()).isEqualTo("PENDING");

        assertThat(storedObjects).containsKey(reserved.tempObjectKey());
        assertThat(storedObjects.get(reserved.tempObjectKey())).isEqualTo(payload);
    }

    @Test
    @DisplayName("Valid image upload stages image with correct MIME and hash")
    void stage_validImageStream_stagesObjectAndRecordsReceipt() throws Exception {
        byte[] payload =
                "image-bytes-png-sample-header-and-body-12345".getBytes(StandardCharsets.UTF_8);
        long size = payload.length;

        ReserveMediaUploadResult reserved =
                reservationService.reserveContentUpload(
                        contentAdminUser.getId(),
                        new ReserveMediaUploadRequest(
                                "diagram.png", "image/png", size, activeSource.getId()));

        ByteArrayInputStream stream = new ByteArrayInputStream(payload);
        StageMediaResult result =
                stagingService.stageMediaUpload(
                        contentAdminUser.getId(), reserved.uploadRequestId(), stream, "image/png");

        assertThat(result.sizeBytes()).isEqualTo(size);
        assertThat(storedObjects).containsKey(reserved.tempObjectKey());
        assertThat(storedObjects.get(reserved.tempObjectKey())).isEqualTo(payload);
    }

    @Test
    @DisplayName("Empty stream fails validation and releases claim so retry is possible")
    void stage_emptyStream_failsAndReleasesClaimForRetry() throws Exception {
        byte[] validPayload = "sample-bytes-100".getBytes(StandardCharsets.UTF_8);
        ReserveMediaUploadResult reserved =
                reservationService.reserveContentUpload(
                        contentAdminUser.getId(),
                        new ReserveMediaUploadRequest(
                                "empty.mp3",
                                "audio/mpeg",
                                (long) validPayload.length,
                                activeSource.getId()));

        ByteArrayInputStream emptyStream = new ByteArrayInputStream(new byte[0]);

        assertThatThrownBy(
                        () ->
                                stagingService.stageMediaUpload(
                                        contentAdminUser.getId(),
                                        reserved.uploadRequestId(),
                                        emptyStream,
                                        "audio/mpeg"))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_ERROR));

        MediaUploadGrant grantAfterFail =
                uploadGrantRepository.findById(reserved.uploadRequestId()).orElseThrow();
        assertThat(grantAfterFail.getPutClaimToken()).isNull();
        assertThat(grantAfterFail.getStagedAt()).isNull();

        ByteArrayInputStream retryStream = new ByteArrayInputStream(validPayload);
        StageMediaResult retryResult =
                stagingService.stageMediaUpload(
                        contentAdminUser.getId(),
                        reserved.uploadRequestId(),
                        retryStream,
                        "audio/mpeg");
        assertThat(retryResult.sizeBytes()).isEqualTo(validPayload.length);
    }

    @Test
    @DisplayName("Stream shorter than declared fails and releases claim for retry")
    void stage_streamShorterThanDeclared_failsAndReleasesClaimForRetry() {
        ReserveMediaUploadResult reserved =
                reservationService.reserveContentUpload(
                        contentAdminUser.getId(),
                        new ReserveMediaUploadRequest(
                                "short.mp3", "audio/mpeg", 100L, activeSource.getId()));

        byte[] shortBytes = "too-short".getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream shortStream = new ByteArrayInputStream(shortBytes);

        assertThatThrownBy(
                        () ->
                                stagingService.stageMediaUpload(
                                        contentAdminUser.getId(),
                                        reserved.uploadRequestId(),
                                        shortStream,
                                        "audio/mpeg"))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_ERROR));

        MediaUploadGrant grantAfterFail =
                uploadGrantRepository.findById(reserved.uploadRequestId()).orElseThrow();
        assertThat(grantAfterFail.getPutClaimToken()).isNull();
    }

    @Test
    @DisplayName("Stream exceeding declared limit fails with PAYLOAD_TOO_LARGE and releases claim")
    void stage_streamExceedingLimit_failsAndReleasesClaimForRetry() {
        ReserveMediaUploadResult reserved =
                reservationService.reserveContentUpload(
                        contentAdminUser.getId(),
                        new ReserveMediaUploadRequest(
                                "over.mp3", "audio/mpeg", 50L, activeSource.getId()));

        byte[] overBytes = new byte[100];
        ByteArrayInputStream overStream = new ByteArrayInputStream(overBytes);

        assertThatThrownBy(
                        () ->
                                stagingService.stageMediaUpload(
                                        contentAdminUser.getId(),
                                        reserved.uploadRequestId(),
                                        overStream,
                                        "audio/mpeg"))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE));

        MediaUploadGrant grantAfterFail =
                uploadGrantRepository.findById(reserved.uploadRequestId()).orElseThrow();
        assertThat(grantAfterFail.getPutClaimToken()).isNull();
    }

    @Test
    @DisplayName(
            "Infinite stream without Content-Length is bounded at declared limit and halts reading")
    void stage_infiniteStream_stopsAtBoundedRead() {
        ReserveMediaUploadResult reserved =
                reservationService.reserveContentUpload(
                        contentAdminUser.getId(),
                        new ReserveMediaUploadRequest(
                                "infinite.mp3", "audio/mpeg", 1000L, activeSource.getId()));

        InputStream infiniteStream =
                new InputStream() {
                    @Override
                    public int read() {
                        return 65; // 'A'
                    }
                };

        assertThatThrownBy(
                        () ->
                                stagingService.stageMediaUpload(
                                        contentAdminUser.getId(),
                                        reserved.uploadRequestId(),
                                        infiniteStream,
                                        "audio/mpeg"))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE));
    }

    @Test
    @DisplayName(
            "Two concurrent callers on same request: single winner, second caller gets 409 CONFLICT")
    void stage_twoCallers_secondCallerFailsWithConflict() throws Exception {
        byte[] payload = "concurrent-upload-bytes-test".getBytes(StandardCharsets.UTF_8);
        ReserveMediaUploadResult reserved =
                reservationService.reserveContentUpload(
                        contentAdminUser.getId(),
                        new ReserveMediaUploadRequest(
                                "race.mp3",
                                "audio/mpeg",
                                (long) payload.length,
                                activeSource.getId()));

        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);

        Future<StageMediaResult> f1 =
                executor.submit(
                        () -> {
                            startGate.await();
                            return stagingService.stageMediaUpload(
                                    contentAdminUser.getId(),
                                    reserved.uploadRequestId(),
                                    new ByteArrayInputStream(payload),
                                    "audio/mpeg");
                        });

        Future<StageMediaResult> f2 =
                executor.submit(
                        () -> {
                            startGate.await();
                            return stagingService.stageMediaUpload(
                                    contentAdminUser.getId(),
                                    reserved.uploadRequestId(),
                                    new ByteArrayInputStream(payload),
                                    "audio/mpeg");
                        });

        startGate.countDown();

        int successCount = 0;
        int conflictCount = 0;

        for (Future<StageMediaResult> f : new Future[] {f1, f2}) {
            try {
                f.get();
                successCount++;
            } catch (ExecutionException e) {
                if (e.getCause() instanceof BusinessException be
                        && be.getErrorCode() == ErrorCode.CONFLICT) {
                    conflictCount++;
                } else {
                    throw e;
                }
            }
        }
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        assertThat(successCount).isEqualTo(1);
        assertThat(conflictCount).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "When storage write fails, claim is retained for reconciliation and subsequent PUT is rejected")
    void stage_whenStorageFails_keepsClaimForReconcile() {
        byte[] payload = "storage-failure-bytes".getBytes(StandardCharsets.UTF_8);
        ReserveMediaUploadResult reserved =
                reservationService.reserveContentUpload(
                        contentAdminUser.getId(),
                        new ReserveMediaUploadRequest(
                                "fail.mp3",
                                "audio/mpeg",
                                (long) payload.length,
                                activeSource.getId()));

        doThrow(new StorageException("Simulated S3 failure"))
                .when(mediaStorage)
                .put(anyString(), any(Path.class), anyString(), anyString());

        assertThatThrownBy(
                        () ->
                                stagingService.stageMediaUpload(
                                        contentAdminUser.getId(),
                                        reserved.uploadRequestId(),
                                        new ByteArrayInputStream(payload),
                                        "audio/mpeg"))
                .isInstanceOf(StorageException.class)
                .hasMessage("Simulated S3 failure");

        MediaUploadGrant grant =
                uploadGrantRepository.findById(reserved.uploadRequestId()).orElseThrow();
        assertThat(grant.getPutClaimToken()).isNotNull();
        assertThat(grant.getStagedAt()).isNull();

        assertThatThrownBy(
                        () ->
                                stagingService.stageMediaUpload(
                                        contentAdminUser.getId(),
                                        reserved.uploadRequestId(),
                                        new ByteArrayInputStream(payload),
                                        "audio/mpeg"))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    @DisplayName(
            "When storage is disabled, stage fails immediately with 503 without touching object")
    void stage_whenStorageDisabled_failsWithoutTouchingObject() {
        when(mediaStorage.isAvailable()).thenReturn(false);

        ReserveMediaUploadResult reserved =
                reservationService.reserveContentUpload(
                        contentAdminUser.getId(),
                        new ReserveMediaUploadRequest(
                                "disabled.mp3", "audio/mpeg", 100L, activeSource.getId()));

        assertThatThrownBy(
                        () ->
                                stagingService.stageMediaUpload(
                                        contentAdminUser.getId(),
                                        reserved.uploadRequestId(),
                                        new ByteArrayInputStream(new byte[100]),
                                        "audio/mpeg"))
                .isInstanceOf(StorageDisabledException.class)
                .hasMessage("Media storage is disabled.");

        MediaUploadGrant grant =
                uploadGrantRepository.findById(reserved.uploadRequestId()).orElseThrow();
        assertThat(grant.getPutClaimToken()).isNull();
    }

    @Test
    @DisplayName("Cross-owner attempt fails with 404 RESOURCE_NOT_FOUND without reading body")
    void stage_crossOwner_failsWithNotFound() {
        ReserveMediaUploadResult reserved =
                reservationService.reserveContentUpload(
                        contentAdminUser.getId(),
                        new ReserveMediaUploadRequest(
                                "owner.mp3", "audio/mpeg", 100L, activeSource.getId()));

        AtomicBoolean streamRead = new AtomicBoolean(false);
        InputStream monitoredStream =
                new ByteArrayInputStream(new byte[100]) {
                    @Override
                    public int read(byte[] b, int off, int len) {
                        streamRead.set(true);
                        return super.read(b, off, len);
                    }
                };

        assertThatThrownBy(
                        () ->
                                stagingService.stageMediaUpload(
                                        secondAdminUser.getId(),
                                        reserved.uploadRequestId(),
                                        monitoredStream,
                                        "audio/mpeg"))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));

        assertThat(streamRead.get()).isFalse();
    }

    @Test
    @DisplayName("Non-content-admin actor fails with 403 ACCESS_DENIED without reading body")
    void stage_nonAdminRole_failsWithAccessDenied() {
        ReserveMediaUploadResult reserved =
                reservationService.reserveContentUpload(
                        contentAdminUser.getId(),
                        new ReserveMediaUploadRequest(
                                "role.mp3", "audio/mpeg", 100L, activeSource.getId()));

        AtomicBoolean streamRead = new AtomicBoolean(false);
        InputStream monitoredStream =
                new ByteArrayInputStream(new byte[100]) {
                    @Override
                    public int read(byte[] b, int off, int len) {
                        streamRead.set(true);
                        return super.read(b, off, len);
                    }
                };

        assertThatThrownBy(
                        () ->
                                stagingService.stageMediaUpload(
                                        learnerUser.getId(),
                                        reserved.uploadRequestId(),
                                        monitoredStream,
                                        "audio/mpeg"))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.ACCESS_DENIED));

        assertThat(streamRead.get()).isFalse();
    }

    @Test
    @DisplayName(
            "Verification confirms no active DB transaction during stream read and storage put")
    void stage_verifiesNoTransactionActiveDuringIO() {
        byte[] payload = "transaction-boundary-test".getBytes(StandardCharsets.UTF_8);
        ReserveMediaUploadResult reserved =
                reservationService.reserveContentUpload(
                        contentAdminUser.getId(),
                        new ReserveMediaUploadRequest(
                                "tx.mp3",
                                "audio/mpeg",
                                (long) payload.length,
                                activeSource.getId()));

        AtomicBoolean txCheckedInStream = new AtomicBoolean(false);
        InputStream checkingStream =
                new ByteArrayInputStream(payload) {
                    @Override
                    public int read(byte[] b, int off, int len) {
                        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                                .as(
                                        "Reading input stream must occur outside any active DB transaction")
                                .isFalse();
                        txCheckedInStream.set(true);
                        return super.read(b, off, len);
                    }
                };

        StageMediaResult result =
                stagingService.stageMediaUpload(
                        contentAdminUser.getId(),
                        reserved.uploadRequestId(),
                        checkingStream,
                        "audio/mpeg");

        assertThat(result.sizeBytes()).isEqualTo(payload.length);
        assertThat(txCheckedInStream.get()).isTrue();
    }

    @Test
    @DisplayName(
            "When called inside outer transaction, suspends outer transaction during stream read and storage put")
    void stage_whenCalledInsideOuterTransaction_suspendsTransactionDuringIO() {
        byte[] payload = "outer-tx-boundary-test".getBytes(StandardCharsets.UTF_8);
        ReserveMediaUploadResult reserved =
                reservationService.reserveContentUpload(
                        contentAdminUser.getId(),
                        new ReserveMediaUploadRequest(
                                "outer-tx.mp3",
                                "audio/mpeg",
                                (long) payload.length,
                                activeSource.getId()));

        AtomicBoolean streamTxChecked = new AtomicBoolean(false);
        InputStream checkingStream =
                new ByteArrayInputStream(payload) {
                    @Override
                    public int read(byte[] b, int off, int len) {
                        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                                .as("Stream reading must not have an active DB transaction")
                                .isFalse();
                        streamTxChecked.set(true);
                        return super.read(b, off, len);
                    }
                };

        StageMediaResult result =
                transactionTemplate.execute(
                        status -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isTrue();
                            return stagingService.stageMediaUpload(
                                    contentAdminUser.getId(),
                                    reserved.uploadRequestId(),
                                    checkingStream,
                                    "audio/mpeg");
                        });

        assertThat(result).isNotNull();
        assertThat(result.sizeBytes()).isEqualTo(payload.length);
        assertThat(streamTxChecked.get()).isTrue();
    }

    @Test
    @DisplayName("Bounded read caps actual bytes read at declared + 1 bytes on oversized stream")
    void stage_boundedRead_readsAtMostDeclaredPlusOneBytes() {
        long declaredSize = 100L;
        ReserveMediaUploadResult reserved =
                reservationService.reserveContentUpload(
                        contentAdminUser.getId(),
                        new ReserveMediaUploadRequest(
                                "bounded.mp3", "audio/mpeg", declaredSize, activeSource.getId()));

        byte[] largePayload = new byte[100_000];
        AtomicLong totalBytesReadCounter = new AtomicLong(0);

        InputStream countingStream =
                new ByteArrayInputStream(largePayload) {
                    @Override
                    public int read(byte[] b, int off, int len) {
                        int r = super.read(b, off, len);
                        if (r > 0) {
                            totalBytesReadCounter.addAndGet(r);
                        }
                        return r;
                    }
                };

        assertThatThrownBy(
                        () ->
                                stagingService.stageMediaUpload(
                                        contentAdminUser.getId(),
                                        reserved.uploadRequestId(),
                                        countingStream,
                                        "audio/mpeg"))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE));

        // Exact bounded read: declaredSize + 1 = 101 bytes
        assertThat(totalBytesReadCounter.get())
                .as("Exact read count must be at most declared + 1 bytes")
                .isEqualTo(declaredSize + 1);
    }

    @Test
    @DisplayName(
            "When grant expires during stream reading, aborts before storage put and releases claim")
    void stage_whenExpiresDuringReading_abortsBeforeStoragePutAndReleasesClaim() {
        byte[] payload = "expire-during-reading-test".getBytes(StandardCharsets.UTF_8);
        ReserveMediaUploadResult reserved =
                reservationService.reserveContentUpload(
                        contentAdminUser.getId(),
                        new ReserveMediaUploadRequest(
                                "expire-pre.mp3",
                                "audio/mpeg",
                                (long) payload.length,
                                activeSource.getId()));

        InputStream expiringStream =
                new ByteArrayInputStream(payload) {
                    @Override
                    public int read(byte[] b, int off, int len) {
                        // Advance clock by 10 minutes past the 5-minute grant expiry
                        clock.advance(Duration.ofMinutes(10));
                        return super.read(b, off, len);
                    }
                };

        assertThatThrownBy(
                        () ->
                                stagingService.stageMediaUpload(
                                        contentAdminUser.getId(),
                                        reserved.uploadRequestId(),
                                        expiringStream,
                                        "audio/mpeg"))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.CONFLICT));

        // Storage put must NEVER be called
        assertThat(storedObjects).doesNotContainKey(reserved.tempObjectKey());

        // Claim must be released so request is not left in an orphaned claim state
        MediaUploadGrant grant =
                uploadGrantRepository.findById(reserved.uploadRequestId()).orElseThrow();
        assertThat(grant.getPutClaimToken()).isNull();
        assertThat(grant.getStagedAt()).isNull();
    }

    @Test
    @DisplayName(
            "When actor role is revoked during stream reading, aborts before storage put and releases claim")
    void stage_whenRoleRevokedDuringReading_abortsBeforeStoragePutAndReleasesClaim() {
        byte[] payload = "role-revoked-pre-put".getBytes(StandardCharsets.UTF_8);
        ReserveMediaUploadResult reserved =
                reservationService.reserveContentUpload(
                        contentAdminUser.getId(),
                        new ReserveMediaUploadRequest(
                                "revoke-pre.mp3",
                                "audio/mpeg",
                                (long) payload.length,
                                activeSource.getId()));

        InputStream revokingStream =
                new ByteArrayInputStream(payload) {
                    @Override
                    public int read(byte[] b, int off, int len) {
                        // Revoke CONTENT_ADMIN role during stream reading
                        transactionTemplate.execute(
                                status -> {
                                    jdbcTemplate.update(
                                            "DELETE FROM user_roles WHERE user_id = ? AND role_id = (SELECT id FROM roles WHERE code = 'CONTENT_ADMIN')",
                                            contentAdminUser.getId());
                                    return null;
                                });
                        return super.read(b, off, len);
                    }
                };

        assertThatThrownBy(
                        () ->
                                stagingService.stageMediaUpload(
                                        contentAdminUser.getId(),
                                        reserved.uploadRequestId(),
                                        revokingStream,
                                        "audio/mpeg"))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.ACCESS_DENIED));

        // Storage put must NEVER be called
        assertThat(storedObjects).doesNotContainKey(reserved.tempObjectKey());

        // Claim must be released
        MediaUploadGrant grant =
                uploadGrantRepository.findById(reserved.uploadRequestId()).orElseThrow();
        assertThat(grant.getPutClaimToken()).isNull();
        assertThat(grant.getStagedAt()).isNull();
    }

    @Test
    @DisplayName(
            "When grant expires after storage put, rejects receipt and preserves claim for reconciliation")
    void stage_whenExpiresAfterStoragePut_rejectsReceiptAndPreservesClaim() {
        byte[] payload = "expire-post-storage-test".getBytes(StandardCharsets.UTF_8);
        ReserveMediaUploadResult reserved =
                reservationService.reserveContentUpload(
                        contentAdminUser.getId(),
                        new ReserveMediaUploadRequest(
                                "expire-post.mp3",
                                "audio/mpeg",
                                (long) payload.length,
                                activeSource.getId()));

        doAnswer(
                        invocation -> {
                            String key = invocation.getArgument(0);
                            Path file = invocation.getArgument(1);
                            storedObjects.put(key, Files.readAllBytes(file));
                            // Advance clock past grant expiry after storage put succeeds
                            clock.advance(Duration.ofMinutes(10));
                            return null;
                        })
                .when(mediaStorage)
                .put(anyString(), any(Path.class), anyString(), anyString());

        assertThatThrownBy(
                        () ->
                                stagingService.stageMediaUpload(
                                        contentAdminUser.getId(),
                                        reserved.uploadRequestId(),
                                        new ByteArrayInputStream(payload),
                                        "audio/mpeg"))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.CONFLICT));

        // Object WAS written to storage
        assertThat(storedObjects).containsKey(reserved.tempObjectKey());

        // Claim MUST be retained for reconciliation, receipt MUST NOT be recorded
        MediaUploadGrant grant =
                uploadGrantRepository.findById(reserved.uploadRequestId()).orElseThrow();
        assertThat(grant.getPutClaimToken()).isNotNull();
        assertThat(grant.getStagedAt()).isNull();
        assertThat(grant.getStagedSizeBytes()).isNull();
    }

    @Test
    @DisplayName(
            "When actor role is revoked after storage put, rejects receipt and preserves claim for reconciliation")
    void stage_whenRoleRevokedAfterStoragePut_rejectsReceiptAndPreservesClaim() {
        byte[] payload = "revoke-post-storage-test".getBytes(StandardCharsets.UTF_8);
        ReserveMediaUploadResult reserved =
                reservationService.reserveContentUpload(
                        contentAdminUser.getId(),
                        new ReserveMediaUploadRequest(
                                "revoke-post.mp3",
                                "audio/mpeg",
                                (long) payload.length,
                                activeSource.getId()));

        doAnswer(
                        invocation -> {
                            String key = invocation.getArgument(0);
                            Path file = invocation.getArgument(1);
                            storedObjects.put(key, Files.readAllBytes(file));
                            // Revoke CONTENT_ADMIN role after storage put succeeds
                            transactionTemplate.execute(
                                    status -> {
                                        jdbcTemplate.update(
                                                "DELETE FROM user_roles WHERE user_id = ? AND role_id = (SELECT id FROM roles WHERE code = 'CONTENT_ADMIN')",
                                                contentAdminUser.getId());
                                        return null;
                                    });
                            return null;
                        })
                .when(mediaStorage)
                .put(anyString(), any(Path.class), anyString(), anyString());

        assertThatThrownBy(
                        () ->
                                stagingService.stageMediaUpload(
                                        contentAdminUser.getId(),
                                        reserved.uploadRequestId(),
                                        new ByteArrayInputStream(payload),
                                        "audio/mpeg"))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.ACCESS_DENIED));

        // Object WAS written to storage
        assertThat(storedObjects).containsKey(reserved.tempObjectKey());

        // Claim MUST be retained for reconciliation, receipt MUST NOT be recorded
        MediaUploadGrant grant =
                uploadGrantRepository.findById(reserved.uploadRequestId()).orElseThrow();
        assertThat(grant.getPutClaimToken()).isNotNull();
        assertThat(grant.getStagedAt()).isNull();
    }

    @TestConfiguration
    static class ClockFixture {
        @Bean
        @Primary
        MutableClock mediaStagingClock() {
            return new MutableClock();
        }
    }

    static class MutableClock extends Clock {
        private final AtomicReference<Instant> fixedInstant = new AtomicReference<>(null);

        void set(Instant value) {
            fixedInstant.set(value);
        }

        void advance(Duration duration) {
            fixedInstant.updateAndGet(
                    current -> (current != null ? current : Instant.now()).plus(duration));
        }

        void reset() {
            fixedInstant.set(null);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            Instant value = fixedInstant.get();
            return value != null ? value : Instant.now();
        }
    }
}
