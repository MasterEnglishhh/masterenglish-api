package com.masterenglish.api.modules.content.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.masterenglish.api.TestcontainersConfiguration;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.common.security.RoleCode;
import com.masterenglish.api.common.storage.MediaStorage;
import com.masterenglish.api.modules.content.dto.request.ReserveMediaUploadRequest;
import com.masterenglish.api.modules.content.dto.response.CompleteMediaResult;
import com.masterenglish.api.modules.content.dto.response.DecodedMediaInfo;
import com.masterenglish.api.modules.content.dto.response.ReserveMediaUploadResult;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIf("isDockerAvailable")
class MediaVerificationIntegrationTests {

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
    @Autowired private MediaVerificationService verificationService;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private ContentSourceRepository sourceRepository;
    @Autowired private MediaAssetRepository mediaAssetRepository;
    @Autowired private MediaUploadGrantRepository uploadGrantRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

    @MockitoBean private MediaStorage mediaStorage;
    @MockitoBean private MediaDecoder mediaDecoder;

    private User contentAdminUser;
    private User secondAdminUser;
    private User thirdAdminUser;
    private User learnerUser;
    private ContentSource activeSource;

    @BeforeEach
    void setUp() {
        reset(mediaStorage);
        when(mediaStorage.isAvailable()).thenReturn(true);
        when(mediaStorage.bucket()).thenReturn("test-media-bucket");

        cleanUpTables();

        transactionTemplate.execute(
                status -> {
                    Role adminRole =
                            roleRepository.findByCode(RoleCode.CONTENT_ADMIN).orElseThrow();
                    Role learnerRole = roleRepository.findByCode(RoleCode.LEARNER).orElseThrow();

                    contentAdminUser =
                            userRepository.save(
                                    new User(
                                            "admin1-verify-" + UUID.randomUUID() + "@example.com",
                                            "$2a$12$D4YF1N6gL/x5U12345678u0w7W.zZ6t1m7U9y9X3mN.rLw5QkOe",
                                            "AdminOne",
                                            "Verify"));
                    contentAdminUser.addRole(adminRole);
                    contentAdminUser.markEmailVerified(Instant.now());
                    contentAdminUser = userRepository.saveAndFlush(contentAdminUser);

                    secondAdminUser =
                            userRepository.save(
                                    new User(
                                            "admin2-verify-" + UUID.randomUUID() + "@example.com",
                                            "$2a$12$D4YF1N6gL/x5U12345678u0w7W.zZ6t1m7U9y9X3mN.rLw5QkOe",
                                            "AdminTwo",
                                            "Verify"));
                    secondAdminUser.addRole(adminRole);
                    secondAdminUser.markEmailVerified(Instant.now());
                    secondAdminUser = userRepository.saveAndFlush(secondAdminUser);

                    thirdAdminUser =
                            userRepository.save(
                                    new User(
                                            "admin3-verify-" + UUID.randomUUID() + "@example.com",
                                            "$2a$12$D4YF1N6gL/x5U12345678u0w7W.zZ6t1m7U9y9X3mN.rLw5QkOe",
                                            "AdminThree",
                                            "Verify"));
                    thirdAdminUser.addRole(adminRole);
                    thirdAdminUser.markEmailVerified(Instant.now());
                    thirdAdminUser = userRepository.saveAndFlush(thirdAdminUser);

                    learnerUser =
                            userRepository.save(
                                    new User(
                                            "learner-verify-" + UUID.randomUUID() + "@example.com",
                                            "$2a$12$D4YF1N6gL/x5U12345678u0w7W.zZ6t1m7U9y9X3mN.rLw5QkOe",
                                            "Learner",
                                            "Verify"));
                    learnerUser.addRole(learnerRole);
                    learnerUser.markEmailVerified(Instant.now());
                    learnerUser = userRepository.saveAndFlush(learnerUser);

                    activeSource =
                            sourceRepository.saveAndFlush(
                                    new ContentSource(
                                            UUID.randomUUID(),
                                            "Internal Media Verification Source",
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

    private ReserveMediaUploadResult createStagedGrant(User user, String filename, byte[] payload) {
        ReserveMediaUploadResult reserved =
                reservationService.reserveContentUpload(
                        user.getId(),
                        new ReserveMediaUploadRequest(
                                filename,
                                "audio/mpeg",
                                (long) payload.length,
                                activeSource.getId()));
        stagingService.stageMediaUpload(
                user.getId(),
                reserved.uploadRequestId(),
                new ByteArrayInputStream(payload),
                "audio/mpeg");
        return reserved;
    }

    @Autowired
    private com.masterenglish.api.modules.content.service.impl.MediaVerificationTxService
            verificationTx;

    @Test
    void concurrentWorkersShareTwoPhysicalSlots() throws Exception {
        List<UUID> tokens = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(tokens.size());
        try {
            var attempts =
                    tokens.stream()
                            .map(
                                    token ->
                                            executor.submit(
                                                    () -> {
                                                        if (!start.await(5, TimeUnit.SECONDS)) {
                                                            throw new IllegalStateException(
                                                                    "Start signal timed out");
                                                        }
                                                        return verificationTx
                                                                .tryAcquirePhysicalSlot(token);
                                                    }))
                            .toList();
            start.countDown();
            for (var attempt : attempts) {
                attempt.get(5, TimeUnit.SECONDS);
            }
            assertThat(
                            jdbcTemplate.queryForObject(
                                    "SELECT count(*) FROM media_verification_slots WHERE worker_token IS NOT NULL",
                                    Integer.class))
                    .isEqualTo(2);
            assertThat(verificationTx.tryAcquirePhysicalSlot(UUID.randomUUID())).isFalse();
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            tokens.forEach(verificationTx::releasePhysicalSlot);
        }
    }

    @Test
    void physicalSlotsStayOccupiedUntilTheirOwnerReleasesThem() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        try {
            assertThat(verificationTx.tryAcquirePhysicalSlot(first)).isTrue();
            assertThat(verificationTx.tryAcquirePhysicalSlot(second)).isTrue();
            verificationTx.releasePhysicalSlot(third);
            assertThat(verificationTx.tryAcquirePhysicalSlot(third)).isFalse();
            verificationTx.releasePhysicalSlot(first);
            assertThat(verificationTx.tryAcquirePhysicalSlot(third)).isTrue();
        } finally {
            verificationTx.releasePhysicalSlot(first);
            verificationTx.releasePhysicalSlot(second);
            verificationTx.releasePhysicalSlot(third);
        }
    }

    @Test
    void physicalSlotSurvivesConnectionLossAndLeaseExpiry() throws Exception {
        byte[] payload = "slot-fencing-audio-payload".getBytes(StandardCharsets.UTF_8);
        ReserveMediaUploadResult r1 = createStagedGrant(contentAdminUser, "slot1.mp3", payload);
        ReserveMediaUploadResult r2 = createStagedGrant(secondAdminUser, "slot2.mp3", payload);
        ReserveMediaUploadResult r3 = createStagedGrant(thirdAdminUser, "slot3.mp3", payload);

        CompleteMediaResult c1 =
                verificationService.completeUpload(contentAdminUser.getId(), r1.uploadRequestId());
        CompleteMediaResult c2 =
                verificationService.completeUpload(secondAdminUser.getId(), r2.uploadRequestId());

        when(mediaStorage.openStream(any()))
                .thenAnswer(invocation -> new ByteArrayInputStream(payload));
        when(mediaStorage.exists(any())).thenReturn(false);

        CountDownLatch firstDecoderEntered = new CountDownLatch(1);
        CountDownLatch twoDecodersEntered = new CountDownLatch(2);
        CountDownLatch releaseWorker1 = new CountDownLatch(1);
        CountDownLatch releaseWorker2 = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger activeDecoders =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger maxConcurrentDecoders =
                new java.util.concurrent.atomic.AtomicInteger();

        when(mediaDecoder.decode(any(Path.class), any()))
                .thenAnswer(
                        invocation -> {
                            int current = activeDecoders.incrementAndGet();
                            maxConcurrentDecoders.accumulateAndGet(current, Math::max);
                            try {
                                twoDecodersEntered.countDown();
                                if (current == 1) {
                                    firstDecoderEntered.countDown();
                                    releaseWorker1.await(10, TimeUnit.SECONDS);
                                } else {
                                    releaseWorker2.await(10, TimeUnit.SECONDS);
                                }
                                return new DecodedMediaInfo(
                                        "audio/mpeg", "mp3", null, null, 1.0, 44100, 2);
                            } finally {
                                activeDecoders.decrementAndGet();
                            }
                        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<CompleteMediaResult> f1 =
                    executor.submit(
                            () ->
                                    verificationService.verifyAndFinalize(
                                            r1.uploadRequestId(), c1.claimToken()));
            assertThat(firstDecoderEntered.await(5, TimeUnit.SECONDS)).isTrue();
            Future<CompleteMediaResult> f2 =
                    executor.submit(
                            () ->
                                    verificationService.verifyAndFinalize(
                                            r2.uploadRequestId(), c2.claimToken()));

            assertThat(twoDecodersEntered.await(5, TimeUnit.SECONDS)).isTrue();

            // Simulate worker 1 lease expiry while worker 1 is still stuck inside decoder
            transactionTemplate.executeWithoutResult(
                    status ->
                            jdbcTemplate.update(
                                    "UPDATE media_upload_requests SET lease_until = NOW() - INTERVAL '1 second' WHERE id = ?",
                                    r1.uploadRequestId()));

            // Even though r1's lease expired (so r3 can claim a verification lease),
            // r3 MUST NOT enter decoder while 2 physical slots are still occupied.
            CompleteMediaResult c3 =
                    verificationService.completeUpload(
                            thirdAdminUser.getId(), r3.uploadRequestId());
            assertThat(c3.leaseAcquired()).isTrue();

            assertThatThrownBy(
                            () ->
                                    verificationService.verifyAndFinalize(
                                            r3.uploadRequestId(), c3.claimToken()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(
                            ex -> {
                                BusinessException be = (BusinessException) ex;
                                assertThat(be.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
                                assertThat(be.getMessage())
                                        .contains("Verification capacity reached");
                            });

            // Now release worker 1: its stale/expired token cannot commit READY,
            // and its finally block releases its physical slot.
            releaseWorker1.countDown();
            assertThatThrownBy(() -> f1.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(BusinessException.class);

            assertThat(mediaAssetRepository.findById(r1.mediaId()).orElseThrow().getStatus())
                    .isEqualTo("PENDING");

            // Now worker 3 acquires the freed slot and succeeds
            CompleteMediaResult r3Result =
                    verificationService.verifyAndFinalize(r3.uploadRequestId(), c3.claimToken());
            assertThat(r3Result.status()).isEqualTo("COMPLETED");
            assertThat(maxConcurrentDecoders.get()).isLessThanOrEqualTo(2);

            releaseWorker2.countDown();
            assertThat(f2.get(5, TimeUnit.SECONDS).status()).isEqualTo("COMPLETED");
        } finally {
            releaseWorker1.countDown();
            releaseWorker2.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName("Non-content-admin actor fails with 403 ACCESS_DENIED")
    void complete_nonAdminRole_failsWithAccessDenied() {
        byte[] payload = "audio-sample-data-12345".getBytes(StandardCharsets.UTF_8);
        ReserveMediaUploadResult reserved =
                createStagedGrant(contentAdminUser, "sample.mp3", payload);

        assertThatThrownBy(
                        () ->
                                verificationService.completeUpload(
                                        learnerUser.getId(), reserved.uploadRequestId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.ACCESS_DENIED));
    }

    @Test
    @DisplayName(
            "Cross-owner attempt fails with 404 RESOURCE_NOT_FOUND without revealing existence")
    void complete_crossOwner_failsWithNotFound() {
        byte[] payload = "audio-sample-data-12345".getBytes(StandardCharsets.UTF_8);
        ReserveMediaUploadResult reserved =
                createStagedGrant(contentAdminUser, "sample.mp3", payload);

        assertThatThrownBy(
                        () ->
                                verificationService.completeUpload(
                                        secondAdminUser.getId(), reserved.uploadRequestId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    @DisplayName("PENDING upload request without staging receipt fails with 409 CONFLICT")
    void complete_pendingWithoutReceipt_failsWithConflict() {
        ReserveMediaUploadResult reserved =
                reservationService.reserveContentUpload(
                        contentAdminUser.getId(),
                        new ReserveMediaUploadRequest(
                                "unstaged.mp3", "audio/mpeg", 100L, activeSource.getId()));

        assertThatThrownBy(
                        () ->
                                verificationService.completeUpload(
                                        contentAdminUser.getId(), reserved.uploadRequestId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    @DisplayName(
            "Negative: DB constraint c14 rejects mismatched staged receipt with SQLSTATE 23514")
    void complete_mismatchedStagedReceipt_rejectedByDatabaseConstraint() {
        ReserveMediaUploadResult reserved =
                reservationService.reserveContentUpload(
                        contentAdminUser.getId(),
                        new ReserveMediaUploadRequest(
                                "malformed.mp3", "audio/mpeg", 100L, activeSource.getId()));

        assertThatThrownBy(
                        () ->
                                transactionTemplate.execute(
                                        status -> {
                                            jdbcTemplate.update(
                                                    "UPDATE media_upload_requests SET put_claim_token = ?, put_claimed_at = NOW(), staged_size_bytes = 50, staged_sha256 = '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', staged_at = NOW() WHERE id = ?",
                                                    UUID.randomUUID(),
                                                    reserved.uploadRequestId());
                                            return null;
                                        }))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(
                        ex -> {
                            Throwable root = ((DataIntegrityViolationException) ex).getRootCause();
                            assertThat(root).isInstanceOf(SQLException.class);
                            SQLException sqlEx = (SQLException) root;
                            assertThat(sqlEx.getSQLState()).isEqualTo("23514");
                            assertThat(sqlEx.getMessage()).contains("media_upload_requests_c14");
                        });
    }

    @Test
    @DisplayName("PENDING upload request past expires_at fails with 409 CONFLICT")
    void complete_pendingPastExpiry_failsWithConflict() {
        UUID mediaId = UUID.randomUUID();
        UUID reqId = UUID.randomUUID();

        // Create legitimately expired PENDING request at insert time (created 5m ago, expired 1m
        // ago)
        transactionTemplate.execute(
                status -> {
                    jdbcTemplate.update(
                            "INSERT INTO media_assets (id, media_type, purpose, original_filename, mime_type, file_size_bytes, uploaded_by, source_id, status) "
                                    + "VALUES (?, 'AUDIO', 'CONTENT', 'expired.mp3', 'audio/mpeg', 100, ?, ?, 'PENDING')",
                            mediaId,
                            contentAdminUser.getId(),
                            activeSource.getId());
                    jdbcTemplate.update(
                            "INSERT INTO media_upload_requests (id, media_id, temp_bucket, temp_object_key, declared_size_bytes, declared_mime_type, max_size_bytes, status, created_at, expires_at, put_claim_token, put_claimed_at, staged_size_bytes, staged_sha256, staged_at) "
                                    + "VALUES (?, ?, 'test-media-bucket', 'temp/expired.mp3', 100, 'audio/mpeg', 20971520, 'PENDING', NOW() - INTERVAL '5 minutes', NOW() - INTERVAL '1 minute', ?, NOW() - INTERVAL '3 minutes', 100, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', NOW() - INTERVAL '3 minutes')",
                            reqId,
                            mediaId,
                            UUID.randomUUID());
                    return null;
                });

        assertThatThrownBy(
                        () -> verificationService.completeUpload(contentAdminUser.getId(), reqId))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    @DisplayName(
            "Valid staged upload transitions to VERIFYING and acquires 60s lease with 5m deadline")
    void complete_validStagedUpload_transitionsToVerifyingAndAcquiresLease() {
        byte[] payload = "valid-audio-data-test-bytes".getBytes(StandardCharsets.UTF_8);
        ReserveMediaUploadResult reserved =
                createStagedGrant(contentAdminUser, "valid.mp3", payload);

        CompleteMediaResult result =
                verificationService.completeUpload(
                        contentAdminUser.getId(), reserved.uploadRequestId());

        assertThat(result.uploadRequestId()).isEqualTo(reserved.uploadRequestId());
        assertThat(result.mediaId()).isEqualTo(reserved.mediaId());
        assertThat(result.status()).isEqualTo("VERIFYING");
        assertThat(result.leaseAcquired()).isTrue();
        assertThat(result.claimToken()).isNotNull();
        assertThat(result.leaseUntil()).isNotNull();
        assertThat(result.verificationDeadlineAt()).isNotNull();

        MediaUploadGrant grant =
                uploadGrantRepository.findById(reserved.uploadRequestId()).orElseThrow();
        assertThat(grant.getStatus()).isEqualTo("VERIFYING");
        assertThat(grant.getClaimToken()).isEqualTo(result.claimToken());
        assertThat(grant.getLeaseUntil()).isEqualTo(result.leaseUntil());
        assertThat(grant.getVerificationDeadlineAt()).isEqualTo(result.verificationDeadlineAt());
        assertThat(grant.getVerificationStartedAt()).isNotNull();

        // Verification deadline is started_at + 5 minutes
        Instant expectedDeadline =
                grant.getVerificationStartedAt()
                        .plus(Duration.ofMinutes(5))
                        .truncatedTo(ChronoUnit.MICROS);
        assertThat(grant.getVerificationDeadlineAt().truncatedTo(ChronoUnit.MICROS))
                .isEqualTo(expectedDeadline);
    }

    @Test
    @DisplayName(
            "Retry while lease is active returns VERIFYING without modifying deadline or claim token")
    void complete_retryWhileLeaseActive_returnsVerifyingWithoutModifyingDeadlineOrToken() {
        byte[] payload = "retry-while-active-bytes".getBytes(StandardCharsets.UTF_8);
        ReserveMediaUploadResult reserved =
                createStagedGrant(contentAdminUser, "retry.mp3", payload);

        CompleteMediaResult firstResult =
                verificationService.completeUpload(
                        contentAdminUser.getId(), reserved.uploadRequestId());
        assertThat(firstResult.leaseAcquired()).isTrue();

        CompleteMediaResult retryResult =
                verificationService.completeUpload(
                        contentAdminUser.getId(), reserved.uploadRequestId());

        assertThat(retryResult.status()).isEqualTo("VERIFYING");
        assertThat(retryResult.leaseAcquired()).isFalse();
        assertThat(retryResult.claimToken()).isNull(); // No new token issued to retry caller
        assertThat(retryResult.leaseUntil()).isEqualTo(firstResult.leaseUntil());
        assertThat(retryResult.verificationDeadlineAt())
                .isEqualTo(firstResult.verificationDeadlineAt());

        MediaUploadGrant grant =
                uploadGrantRepository.findById(reserved.uploadRequestId()).orElseThrow();
        assertThat(grant.getClaimToken()).isEqualTo(firstResult.claimToken());
        assertThat(grant.getVerificationDeadlineAt())
                .isEqualTo(firstResult.verificationDeadlineAt());
    }

    @Test
    void failedRetryKeepsContentAllocationWhenFinalMayExist() {
        byte[] payload = "final-write-test".getBytes(StandardCharsets.UTF_8);
        ReserveMediaUploadResult reserved =
                createStagedGrant(contentAdminUser, "final.mp3", payload);
        UUID requestId = reserved.uploadRequestId();
        String tempKey = uploadGrantRepository.findById(requestId).orElseThrow().getTempObjectKey();
        String finalKey = "content/" + reserved.mediaId() + ".mp3";
        when(mediaStorage.openStream(tempKey))
                .thenAnswer(call -> new ByteArrayInputStream(payload));
        when(mediaDecoder.decode(any(), eq("audio/mpeg")))
                .thenReturn(new DecodedMediaInfo("audio/mpeg", "mp3", null, null, 2.5, 44100, 2))
                .thenThrow(new IllegalStateException("decoder unavailable"));
        AtomicReference<byte[]> stored = new AtomicReference<>();
        doAnswer(
                        call -> {
                            stored.set(Files.readAllBytes(call.<Path>getArgument(1)));
                            throw new BusinessException(
                                    ErrorCode.SERVICE_UNAVAILABLE, "PUT response lost");
                        })
                .when(mediaStorage)
                .putIfAbsent(eq(finalKey), any(), eq("audio/mpeg"), any());

        assertThatThrownBy(
                        () ->
                                verificationService.completeAndVerify(
                                        contentAdminUser.getId(), requestId))
                .isInstanceOf(BusinessException.class)
                .hasMessage("PUT response lost");
        jdbcTemplate.update(
                "UPDATE media_upload_requests SET lease_until = NOW() - INTERVAL '10 seconds' WHERE id = ?",
                requestId);
        assertThatThrownBy(
                        () ->
                                verificationService.completeAndVerify(
                                        contentAdminUser.getId(), requestId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("decoder unavailable");

        assertThat(stored.get()).containsExactly(payload);
        assertThat(uploadGrantRepository.findById(requestId).orElseThrow().getStatus())
                .isEqualTo("VERIFYING");
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT state FROM storage_allocations WHERE allocation_key = ?",
                                String.class,
                                "req:" + requestId + ":content"))
                .isEqualTo("RESERVED");
    }

    @Test
    @DisplayName("Retry after lease expired within deadline takes over lease with new claim token")
    void complete_retryAfterLeaseExpiredWithinDeadline_takesOverLeaseWithNewToken() {
        byte[] payload = "lease-takeover-bytes".getBytes(StandardCharsets.UTF_8);
        ReserveMediaUploadResult reserved =
                createStagedGrant(contentAdminUser, "takeover.mp3", payload);

        CompleteMediaResult firstResult =
                verificationService.completeUpload(
                        contentAdminUser.getId(), reserved.uploadRequestId());
        assertThat(firstResult.leaseAcquired()).isTrue();

        // Expire lease in DB (lease_until set to past, but deadline still 5m in the future)
        transactionTemplate.execute(
                status -> {
                    jdbcTemplate.update(
                            "UPDATE media_upload_requests SET lease_until = NOW() - INTERVAL '10 seconds' WHERE id = ?",
                            reserved.uploadRequestId());
                    return null;
                });

        CompleteMediaResult secondResult =
                verificationService.completeUpload(
                        contentAdminUser.getId(), reserved.uploadRequestId());

        assertThat(secondResult.status()).isEqualTo("VERIFYING");
        assertThat(secondResult.leaseAcquired()).isTrue();
        assertThat(secondResult.claimToken()).isNotNull();
        assertThat(secondResult.claimToken()).isNotEqualTo(firstResult.claimToken());
        assertThat(secondResult.verificationDeadlineAt())
                .isEqualTo(firstResult.verificationDeadlineAt());

        MediaUploadGrant grant =
                uploadGrantRepository.findById(reserved.uploadRequestId()).orElseThrow();
        assertThat(grant.getClaimToken()).isEqualTo(secondResult.claimToken());
        assertThat(grant.getVerificationDeadlineAt())
                .isEqualTo(firstResult.verificationDeadlineAt());
    }

    @Test
    @DisplayName("Lease duration is capped at verification deadline when near deadline")
    void complete_leaseCappedAtVerificationDeadline_whenCloseToDeadline() {
        UUID mediaId = UUID.randomUUID();
        UUID reqId = UUID.randomUUID();

        // Insert grant directly in VERIFYING state where deadline is in 20 seconds and lease is in
        // the past
        transactionTemplate.execute(
                status -> {
                    jdbcTemplate.update(
                            "INSERT INTO media_assets (id, media_type, purpose, original_filename, mime_type, file_size_bytes, uploaded_by, source_id, status) "
                                    + "VALUES (?, 'AUDIO', 'CONTENT', 'cap.mp3', 'audio/mpeg', 100, ?, ?, 'PENDING')",
                            mediaId,
                            contentAdminUser.getId(),
                            activeSource.getId());
                    jdbcTemplate.update(
                            "INSERT INTO media_upload_requests (id, media_id, temp_bucket, temp_object_key, declared_size_bytes, declared_mime_type, max_size_bytes, status, created_at, expires_at, verification_started_at, verification_deadline_at, claim_token, lease_until, put_claim_token, put_claimed_at, staged_size_bytes, staged_sha256, staged_at) "
                                    + "VALUES (?, ?, 'test-media-bucket', 'temp/cap.mp3', 100, 'audio/mpeg', 20971520, 'VERIFYING', NOW() - INTERVAL '4 minutes 50 seconds', NOW() + INTERVAL '4 minutes', NOW() - INTERVAL '4 minutes 40 seconds', NOW() + INTERVAL '20 seconds', ?, NOW() - INTERVAL '10 seconds', ?, NOW() - INTERVAL '4 minutes 40 seconds', 100, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', NOW() - INTERVAL '4 minutes 40 seconds')",
                            reqId,
                            mediaId,
                            UUID.randomUUID(),
                            UUID.randomUUID());
                    return null;
                });

        CompleteMediaResult takeoverResult =
                verificationService.completeUpload(contentAdminUser.getId(), reqId);

        assertThat(takeoverResult.leaseAcquired()).isTrue();
        // Capped at verificationDeadlineAt rather than wallTime + 60s
        MediaUploadGrant grant = uploadGrantRepository.findById(reqId).orElseThrow();
        assertThat(grant.getLeaseUntil()).isEqualTo(grant.getVerificationDeadlineAt());
        assertThat(takeoverResult.leaseUntil()).isEqualTo(grant.getVerificationDeadlineAt());
    }

    @Test
    @DisplayName(
            "Complete call after verification deadline passed fails with 409 CONFLICT without mutating")
    void complete_afterVerificationDeadlinePassed_failsWithConflict() {
        UUID mediaId = UUID.randomUUID();
        UUID reqId = UUID.randomUUID();

        // Insert grant in VERIFYING state where deadline passed 1 minute ago
        transactionTemplate.execute(
                status -> {
                    jdbcTemplate.update(
                            "INSERT INTO media_assets (id, media_type, purpose, original_filename, mime_type, file_size_bytes, uploaded_by, source_id, status) "
                                    + "VALUES (?, 'AUDIO', 'CONTENT', 'past-dl.mp3', 'audio/mpeg', 100, ?, ?, 'PENDING')",
                            mediaId,
                            contentAdminUser.getId(),
                            activeSource.getId());
                    jdbcTemplate.update(
                            "INSERT INTO media_upload_requests (id, media_id, temp_bucket, temp_object_key, declared_size_bytes, declared_mime_type, max_size_bytes, status, created_at, expires_at, verification_started_at, verification_deadline_at, claim_token, lease_until, put_claim_token, put_claimed_at, staged_size_bytes, staged_sha256, staged_at) "
                                    + "VALUES (?, ?, 'test-media-bucket', 'temp/past-dl.mp3', 100, 'audio/mpeg', 20971520, 'VERIFYING', NOW() - INTERVAL '7 minutes', NOW() + INTERVAL '2 minutes', NOW() - INTERVAL '6 minutes', NOW() - INTERVAL '1 minute', ?, NOW() - INTERVAL '5 minutes', ?, NOW() - INTERVAL '6 minutes', 100, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', NOW() - INTERVAL '6 minutes')",
                            reqId,
                            mediaId,
                            UUID.randomUUID(),
                            UUID.randomUUID());
                    return null;
                });

        assertThatThrownBy(
                        () -> verificationService.completeUpload(contentAdminUser.getId(), reqId))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.CONFLICT));

        MediaUploadGrant grant = uploadGrantRepository.findById(reqId).orElseThrow();
        assertThat(grant.getStatus()).isEqualTo("VERIFYING");
    }

    @Test
    @DisplayName(
            "Terminal states (COMPLETED, FAILED, EXPIRED) return existing state idempotently without mutation")
    void complete_terminalStates_returnExistingStateWithoutMutation() {
        byte[] payload = "terminal-state-test".getBytes(StandardCharsets.UTF_8);
        ReserveMediaUploadResult reserved1 =
                createStagedGrant(contentAdminUser, "comp.mp3", payload);
        ReserveMediaUploadResult reserved2 =
                createStagedGrant(contentAdminUser, "fail.mp3", payload);
        ReserveMediaUploadResult reserved3 =
                createStagedGrant(contentAdminUser, "exp.mp3", payload);

        transactionTemplate.execute(
                status -> {
                    // 1. COMPLETED: PENDING -> VERIFYING -> COMPLETED, media status -> READY
                    jdbcTemplate.update(
                            "UPDATE media_upload_requests SET status = 'VERIFYING', verification_started_at = NOW(), "
                                    + "verification_deadline_at = NOW() + INTERVAL '5 min', claim_token = ?, lease_until = NOW() + INTERVAL '60 s' WHERE id = ?",
                            UUID.randomUUID(),
                            reserved1.uploadRequestId());
                    jdbcTemplate.update(
                            "UPDATE media_assets SET status = 'READY', bucket = 'test-media-bucket', object_key = 'ready.mp3', "
                                    + "mime_type = 'audio/mpeg', file_size_bytes = ?, checksum_sha256 = '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', duration_ms = 1000 WHERE id = ?",
                            (long) payload.length,
                            reserved1.mediaId());
                    jdbcTemplate.update(
                            "UPDATE media_upload_requests SET status = 'COMPLETED', completed_at = NOW(), finished_at = NOW(), "
                                    + "claim_token = NULL, lease_until = NULL WHERE id = ?",
                            reserved1.uploadRequestId());

                    // 2. FAILED: PENDING -> FAILED, media status -> FAILED
                    jdbcTemplate.update(
                            "UPDATE media_assets SET status = 'FAILED', failure_code = 'DECODE_ERROR' WHERE id = ?",
                            reserved2.mediaId());
                    jdbcTemplate.update(
                            "UPDATE media_upload_requests SET status = 'FAILED', failure_code = 'DECODE_ERROR', finished_at = NOW() WHERE id = ?",
                            reserved2.uploadRequestId());

                    // 3. EXPIRED: PENDING -> EXPIRED, media status -> FAILED
                    jdbcTemplate.update(
                            "UPDATE media_assets SET status = 'FAILED', failure_code = 'TTL_EXPIRED' WHERE id = ?",
                            reserved3.mediaId());
                    jdbcTemplate.update(
                            "UPDATE media_upload_requests SET status = 'EXPIRED', failure_code = 'TTL_EXPIRED', finished_at = NOW() WHERE id = ?",
                            reserved3.uploadRequestId());
                    return null;
                });

        CompleteMediaResult r1 =
                verificationService.completeUpload(
                        contentAdminUser.getId(), reserved1.uploadRequestId());
        assertThat(r1.status()).isEqualTo("COMPLETED");
        assertThat(r1.leaseAcquired()).isFalse();
        assertThat(r1.claimToken()).isNull();

        CompleteMediaResult r2 =
                verificationService.completeUpload(
                        contentAdminUser.getId(), reserved2.uploadRequestId());
        assertThat(r2.status()).isEqualTo("FAILED");
        assertThat(r2.leaseAcquired()).isFalse();
        assertThat(r2.claimToken()).isNull();

        CompleteMediaResult r3 =
                verificationService.completeUpload(
                        contentAdminUser.getId(), reserved3.uploadRequestId());
        assertThat(r3.status()).isEqualTo("EXPIRED");
        assertThat(r3.leaseAcquired()).isFalse();
        assertThat(r3.claimToken()).isNull();
    }

    @Test
    @DisplayName(
            "Global capacity limit: 2 active claims permitted across actors, third denied with 409 CONFLICT")
    void complete_twoActiveClaims_thirdDeniedAcrossActors() {
        byte[] payload = "global-capacity-test".getBytes(StandardCharsets.UTF_8);
        ReserveMediaUploadResult r1 = createStagedGrant(contentAdminUser, "a1.mp3", payload);
        ReserveMediaUploadResult r2 = createStagedGrant(secondAdminUser, "a2.mp3", payload);
        ReserveMediaUploadResult r3 = createStagedGrant(thirdAdminUser, "a3.mp3", payload);

        // 1. First claim succeeds
        CompleteMediaResult res1 =
                verificationService.completeUpload(contentAdminUser.getId(), r1.uploadRequestId());
        assertThat(res1.leaseAcquired()).isTrue();

        // 2. Second claim succeeds
        CompleteMediaResult res2 =
                verificationService.completeUpload(secondAdminUser.getId(), r2.uploadRequestId());
        assertThat(res2.leaseAcquired()).isTrue();

        // 3. Third claim across actors is denied with CONFLICT
        assertThatThrownBy(
                        () ->
                                verificationService.completeUpload(
                                        thirdAdminUser.getId(), r3.uploadRequestId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex -> {
                            BusinessException be = (BusinessException) ex;
                            assertThat(be.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
                            assertThat(be.getMessage()).contains("Verification capacity reached");
                        });

        // 4. Expire r1's lease in DB
        transactionTemplate.execute(
                status -> {
                    jdbcTemplate.update(
                            "UPDATE media_upload_requests SET lease_until = NOW() - INTERVAL '5 seconds' WHERE id = ?",
                            r1.uploadRequestId());
                    return null;
                });

        // Now third claim can acquire a lease!
        CompleteMediaResult res3 =
                verificationService.completeUpload(thirdAdminUser.getId(), r3.uploadRequestId());
        assertThat(res3.leaseAcquired()).isTrue();
        assertThat(res3.status()).isEqualTo("VERIFYING");
    }

    @Test
    @DisplayName(
            "AC27: Concurrent race for last remaining verification slot: holder blocks contender on TEMP pool lock, winner commits, contender fails with CONFLICT without mutation")
    void complete_concurrentRaceForLastSlot_singleWinner() throws Exception {
        byte[] payload = "concurrent-slot-race".getBytes(StandardCharsets.UTF_8);
        // 1. Occupy 1 slot first (leaving exactly 1 available slot out of 2)
        ReserveMediaUploadResult rOccupied =
                createStagedGrant(contentAdminUser, "occ.mp3", payload);
        CompleteMediaResult rOccRes =
                verificationService.completeUpload(
                        contentAdminUser.getId(), rOccupied.uploadRequestId());
        assertThat(rOccRes.leaseAcquired()).isTrue();

        // 2. Prepare 2 candidate grants for two distinct actors racing for the last slot
        ReserveMediaUploadResult rWinner =
                createStagedGrant(secondAdminUser, "winner.mp3", payload);
        ReserveMediaUploadResult rLoser = createStagedGrant(thirdAdminUser, "loser.mp3", payload);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch lockAcquiredLatch = new CountDownLatch(1);
        CountDownLatch releaseLockLatch = new CountDownLatch(1);
        CountDownLatch waiterStartedLatch = new CountDownLatch(1);
        AtomicReference<Long> blockerPidRef = new AtomicReference<>();
        AtomicReference<Long> waiterPidRef = new AtomicReference<>();
        AtomicReference<CompleteMediaResult> winnerResultRef = new AtomicReference<>();

        try {
            // Thread 1 (Holder Actor A - secondAdminUser):
            // Completes upload inside outer tx, claiming the last slot and holding TEMP pool lock
            // until releaseLockLatch
            Future<CompleteMediaResult> holderFuture =
                    executor.submit(
                            () ->
                                    transactionTemplate.execute(
                                            status -> {
                                                Long pid =
                                                        jdbcTemplate.queryForObject(
                                                                "SELECT pg_backend_pid()",
                                                                Long.class);
                                                blockerPidRef.set(pid);

                                                CompleteMediaResult res =
                                                        verificationService.completeUpload(
                                                                secondAdminUser.getId(),
                                                                rWinner.uploadRequestId());
                                                winnerResultRef.set(res);

                                                lockAcquiredLatch.countDown();
                                                try {
                                                    boolean released =
                                                            releaseLockLatch.await(
                                                                    5, TimeUnit.SECONDS);
                                                    if (!released) {
                                                        throw new IllegalStateException(
                                                                "Holder timed out waiting for release latch");
                                                    }
                                                } catch (InterruptedException e) {
                                                    Thread.currentThread().interrupt();
                                                    throw new RuntimeException(e);
                                                }
                                                return res;
                                            }));

            assertThat(lockAcquiredLatch.await(5, TimeUnit.SECONDS)).isTrue();
            Long blockerPid = blockerPidRef.get();
            assertThat(blockerPid).isNotNull();

            // Thread 2 (Contender Actor B - thirdAdminUser):
            // Concurrently attempts to complete upload; must block on TEMP pool lock held by Thread
            // 1
            Future<CompleteMediaResult> contenderFuture =
                    executor.submit(
                            () ->
                                    transactionTemplate.execute(
                                            status -> {
                                                Long pid =
                                                        jdbcTemplate.queryForObject(
                                                                "SELECT pg_backend_pid()",
                                                                Long.class);
                                                waiterPidRef.set(pid);
                                                waiterStartedLatch.countDown();

                                                return verificationService.completeUpload(
                                                        thirdAdminUser.getId(),
                                                        rLoser.uploadRequestId());
                                            }));

            assertThat(waiterStartedLatch.await(5, TimeUnit.SECONDS)).isTrue();
            Long waiterPid = waiterPidRef.get();
            assertThat(waiterPid).isNotNull();

            // Observable verification: Prove Actor B (waiterPid) is genuinely blocked on TEMP pool
            // lock by Actor A (blockerPid)
            assertThat(awaitBlockedBy(waiterPid, blockerPid, 5))
                    .as("Actor B must be blocked by Actor A on storage_pools TEMP lock")
                    .isTrue();
            assertThat(contenderFuture.isDone()).isFalse();

            // Release Thread 1 (Holder) to commit its claim
            releaseLockLatch.countDown();
            CompleteMediaResult winnerResult = holderFuture.get(5, TimeUnit.SECONDS);

            assertThat(winnerResult.leaseAcquired()).isTrue();
            assertThat(winnerResult.status()).isEqualTo("VERIFYING");
            assertThat(winnerResult.claimToken()).isNotNull();
            assertThat(winnerResult.leaseUntil()).isNotNull();
            assertThat(winnerResult.verificationDeadlineAt()).isNotNull();

            // Thread 2 unblocks under TEMP pool lock, sees 2 active verifications, and fails with
            // CONFLICT
            ExecutionException ex =
                    assertThrows(
                            ExecutionException.class,
                            () -> contenderFuture.get(5, TimeUnit.SECONDS));
            assertThat(ex.getCause()).isInstanceOf(BusinessException.class);
            BusinessException be = (BusinessException) ex.getCause();
            assertThat(be.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
            assertThat(be.getMessage()).contains("Verification capacity reached");

            // Assert Actor B's grant was NOT mutated
            MediaUploadGrant loserGrant =
                    uploadGrantRepository.findById(rLoser.uploadRequestId()).orElseThrow();
            assertThat(loserGrant.getStatus()).isEqualTo("PENDING");
            assertThat(loserGrant.getClaimToken()).isNull();
            assertThat(loserGrant.getLeaseUntil()).isNull();
            assertThat(loserGrant.getVerificationStartedAt()).isNull();
            assertThat(loserGrant.getVerificationDeadlineAt()).isNull();

            // Assert exactly 2 active verifications in DB
            long activeCount =
                    uploadGrantRepository.countActiveVerifications(
                            UUID.randomUUID(), Instant.now());
            assertThat(activeCount).isEqualTo(2L);

        } finally {
            releaseLockLatch.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName(
            "AC26/AC27 timing regression: Outer tx with pg_sleep forces txTime < wallTime, caps lease at txTime + 60s without violating V15 trigger, and computes deadline from wall time")
    void complete_timingRegression_outerTxWithPgSleep_capsLeaseAtTxTimeWithoutViolatingTrigger() {
        byte[] payload = "timing-regression-payload".getBytes(StandardCharsets.UTF_8);
        ReserveMediaUploadResult reserved =
                createStagedGrant(contentAdminUser, "timing.mp3", payload);

        AtomicReference<Instant> txTimeRef = new AtomicReference<>();
        AtomicReference<Instant> wallTimeBeforeRef = new AtomicReference<>();

        CompleteMediaResult result =
                transactionTemplate.execute(
                        status -> {
                            // Delay inside transaction to make transaction_timestamp() (txTime)
                            // strictly older than clock_timestamp() (wallTime)
                            jdbcTemplate.execute("SELECT pg_sleep(0.15)");

                            MediaUploadGrantRepository.DbTimestamps dbTimes =
                                    uploadGrantRepository.getDbTimestamps();
                            Instant txTime = dbTimes.getTxTime().truncatedTo(ChronoUnit.MICROS);
                            Instant wallTimeBefore =
                                    dbTimes.getWallTime().truncatedTo(ChronoUnit.MICROS);

                            txTimeRef.set(txTime);
                            wallTimeBeforeRef.set(wallTimeBefore);

                            // Complete upload inside this outer transaction
                            return verificationService.completeUpload(
                                    contentAdminUser.getId(), reserved.uploadRequestId());
                        });

        assertThat(result.leaseAcquired()).isTrue();
        assertThat(result.status()).isEqualTo("VERIFYING");

        Instant txTime = txTimeRef.get();
        Instant wallTimeBefore = wallTimeBeforeRef.get();
        assertThat(wallTimeBefore).isAfterOrEqualTo(txTime.plusMillis(100));

        MediaUploadGrant grant =
                uploadGrantRepository.findById(reserved.uploadRequestId()).orElseThrow();
        Instant startedAt = grant.getVerificationStartedAt();
        Instant deadlineAt = grant.getVerificationDeadlineAt();
        Instant leaseUntil = grant.getLeaseUntil();

        // 1. Verify starts/deadline are calculated from wall time, NOT from tx start time
        assertThat(startedAt).isAfterOrEqualTo(wallTimeBefore);
        assertThat(startedAt).isAfterOrEqualTo(txTime.plusMillis(100));
        assertThat(deadlineAt).isEqualTo(startedAt.plus(Duration.ofMinutes(5)));
        assertThat(deadlineAt).isAfter(txTime.plus(Duration.ofMinutes(5)));

        // 2. lease_until is capped at txTime + 60s to satisfy V15 me_guard_upload_request trigger
        Instant maxTxLease = txTime.plus(Duration.ofSeconds(60)).truncatedTo(ChronoUnit.MICROS);
        assertThat(leaseUntil.truncatedTo(ChronoUnit.MICROS)).isBeforeOrEqualTo(maxTxLease);

        // 3. lease_until is strictly greater than startedAt (positive remaining window)
        assertThat(leaseUntil).isAfter(startedAt);

        // 4. Because txTime is ~150ms older than wallTime, leaseUntil is strictly less than
        // startedAt + 60s
        assertThat(leaseUntil).isBefore(startedAt.plus(Duration.ofSeconds(60)));
    }

    @Test
    @DisplayName(
            "Regression: Reserve limit (3 open requests) counts VERIFYING within deadline alongside PENDING")
    void reserve_openRequestsRegressionTest_countsVerifyingWithinDeadline() {
        byte[] payload = "regression-open-count".getBytes(StandardCharsets.UTF_8);

        // Create 1st request and move to VERIFYING (within deadline)
        ReserveMediaUploadResult r1 = createStagedGrant(contentAdminUser, "reg1.mp3", payload);
        verificationService.completeUpload(contentAdminUser.getId(), r1.uploadRequestId());

        // Create 2nd request in PENDING
        reservationService.reserveContentUpload(
                contentAdminUser.getId(),
                new ReserveMediaUploadRequest(
                        "reg2.mp3", "audio/mpeg", 100L, activeSource.getId()));

        // Create 3rd request in PENDING
        reservationService.reserveContentUpload(
                contentAdminUser.getId(),
                new ReserveMediaUploadRequest(
                        "reg3.mp3", "audio/mpeg", 100L, activeSource.getId()));

        // Attempting 4th request must fail with CONFLICT (3 open requests: 1 VERIFYING + 2 PENDING)
        assertThatThrownBy(
                        () ->
                                reservationService.reserveContentUpload(
                                        contentAdminUser.getId(),
                                        new ReserveMediaUploadRequest(
                                                "reg4.mp3",
                                                "audio/mpeg",
                                                100L,
                                                activeSource.getId())))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.CONFLICT));

        // Mark r1 as COMPLETED (terminal state)
        transactionTemplate.execute(
                status -> {
                    jdbcTemplate.update(
                            "UPDATE media_assets SET status = 'READY', bucket = 'test-media-bucket', object_key = 'reg1.mp3', "
                                    + "mime_type = 'audio/mpeg', file_size_bytes = ?, checksum_sha256 = '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', duration_ms = 1000 WHERE id = ?",
                            (long) payload.length,
                            r1.mediaId());
                    jdbcTemplate.update(
                            "UPDATE media_upload_requests SET status = 'COMPLETED', completed_at = NOW(), finished_at = NOW(), "
                                    + "claim_token = NULL, lease_until = NULL WHERE id = ?",
                            r1.uploadRequestId());
                    return null;
                });

        // Now r1 is COMPLETED (terminal, not open), so open count drops to 2 (the 2 PENDING
        // requests are still open)
        // Reserving 4th request must now SUCCEED!
        ReserveMediaUploadResult r4 =
                reservationService.reserveContentUpload(
                        contentAdminUser.getId(),
                        new ReserveMediaUploadRequest(
                                "reg4-retry.mp3", "audio/mpeg", 100L, activeSource.getId()));
        assertThat(r4).isNotNull();
        assertThat(r4.uploadRequestId()).isNotNull();
    }

    private boolean awaitBlockedBy(Long waiterPid, Long blockerPid, int timeoutSeconds)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            Boolean blocked =
                    jdbcTemplate.queryForObject(
                            "SELECT EXISTS(SELECT 1 FROM unnest(pg_blocking_pids(CAST(? AS integer))) AS blocker(pid) WHERE blocker.pid = CAST(? AS integer))",
                            Boolean.class,
                            waiterPid,
                            blockerPid);
            if (Boolean.TRUE.equals(blocked)) {
                return true;
            }
            Thread.sleep(25);
        }
        return false;
    }
}
