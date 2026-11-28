package com.masterenglish.api.modules.content.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.masterenglish.api.TestcontainersConfiguration;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.common.security.RoleCode;
import com.masterenglish.api.modules.content.dto.request.ReserveMediaUploadRequest;
import com.masterenglish.api.modules.content.dto.response.ReserveMediaUploadResult;
import com.masterenglish.api.modules.content.entity.ContentSource;
import com.masterenglish.api.modules.content.repository.ContentSourceRepository;
import com.masterenglish.api.modules.content.repository.MediaAssetRepository;
import com.masterenglish.api.modules.content.repository.MediaUploadGrantRepository;
import com.masterenglish.api.modules.content.repository.StorageAllocationRepository;
import com.masterenglish.api.modules.content.repository.StoragePoolRepository;
import com.masterenglish.api.modules.content.service.impl.MediaReservationServiceImpl;
import com.masterenglish.api.modules.identity.entity.Role;
import com.masterenglish.api.modules.identity.entity.User;
import com.masterenglish.api.modules.identity.repository.RoleRepository;
import com.masterenglish.api.modules.identity.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIf("isDockerAvailable")
class MediaReservationIntegrationTests {

    static boolean isDockerAvailable() {
        try {
            org.testcontainers.DockerClientFactory.instance().client();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static final UUID CONTENT_POOL_ID = MediaReservationServiceImpl.CONTENT_POOL_ID;
    private static final UUID TEMP_POOL_ID = MediaReservationServiceImpl.TEMP_POOL_ID;

    @Autowired private MediaReservationService reservationService;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private ContentSourceRepository sourceRepository;
    @Autowired private MediaAssetRepository mediaAssetRepository;
    @Autowired private MediaUploadGrantRepository uploadGrantRepository;
    @Autowired private StorageAllocationRepository storageAllocationRepository;
    @Autowired private StoragePoolRepository storagePoolRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

    private User contentAdminUser;
    private User learnerUser;
    private User systemAdminUser;
    private User inactiveAdminUser;
    private ContentSource activeSource;
    private ContentSource archivedSource;

    @BeforeEach
    void setUp() {
        cleanUpTables();
        transactionTemplate.execute(
                status -> {
                    Role adminRole =
                            roleRepository.findByCode(RoleCode.CONTENT_ADMIN).orElseThrow();
                    Role learnerRole = roleRepository.findByCode(RoleCode.LEARNER).orElseThrow();
                    Role sysAdminRole =
                            roleRepository.findByCode(RoleCode.SYSTEM_ADMIN).orElseThrow();

                    contentAdminUser =
                            userRepository.save(
                                    new User(
                                            "admin-media-" + UUID.randomUUID() + "@example.com",
                                            "$2a$12$D4YF1N6gL/x5U12345678u0w7W.zZ6t1m7U9y9X3mN.rLw5QkOe",
                                            "ContentAdmin",
                                            "Media"));
                    contentAdminUser.addRole(adminRole);
                    contentAdminUser.markEmailVerified(Instant.now());
                    contentAdminUser = userRepository.saveAndFlush(contentAdminUser);

                    inactiveAdminUser =
                            userRepository.save(
                                    new User(
                                            "inactive-admin-" + UUID.randomUUID() + "@example.com",
                                            "$2a$12$D4YF1N6gL/x5U12345678u0w7W.zZ6t1m7U9y9X3mN.rLw5QkOe",
                                            "InactiveAdmin",
                                            "Media"));
                    inactiveAdminUser.addRole(adminRole);
                    inactiveAdminUser.lock(Instant.now().plusSeconds(3600));
                    inactiveAdminUser = userRepository.saveAndFlush(inactiveAdminUser);

                    learnerUser =
                            userRepository.save(
                                    new User(
                                            "learner-media-" + UUID.randomUUID() + "@example.com",
                                            "$2a$12$D4YF1N6gL/x5U12345678u0w7W.zZ6t1m7U9y9X3mN.rLw5QkOe",
                                            "Learner",
                                            "Media"));
                    learnerUser.addRole(learnerRole);
                    learnerUser.markEmailVerified(Instant.now());
                    learnerUser = userRepository.saveAndFlush(learnerUser);

                    systemAdminUser =
                            userRepository.save(
                                    new User(
                                            "sysadmin-media-" + UUID.randomUUID() + "@example.com",
                                            "$2a$12$D4YF1N6gL/x5U12345678u0w7W.zZ6t1m7U9y9X3mN.rLw5QkOe",
                                            "SysAdmin",
                                            "Media"));
                    systemAdminUser.addRole(sysAdminRole);
                    systemAdminUser.markEmailVerified(Instant.now());
                    systemAdminUser = userRepository.saveAndFlush(systemAdminUser);

                    activeSource =
                            sourceRepository.saveAndFlush(
                                    new ContentSource(
                                            UUID.randomUUID(),
                                            "Standard Media Source",
                                            "SELF_AUTHORED",
                                            "https://example.com/source",
                                            "MasterEnglish Media Team",
                                            "SELF_AUTHORED",
                                            "https://example.com/license",
                                            "Attribution Note",
                                            "Internal Permission",
                                            "ACTIVE",
                                            contentAdminUser.getId()));

                    archivedSource =
                            sourceRepository.saveAndFlush(
                                    new ContentSource(
                                            UUID.randomUUID(),
                                            "Archived Media Source",
                                            "SELF_AUTHORED",
                                            "https://example.com/source-archived",
                                            "MasterEnglish Media Team",
                                            "SELF_AUTHORED",
                                            "https://example.com/license",
                                            "Attribution Note",
                                            "Internal Permission",
                                            "ARCHIVED",
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

    @Test
    @DisplayName(
            "AC10/AC11/AC24: Content admin successfully reserves image upload with server-side temp key")
    void contentAdminSuccessfullyReservesImageUpload() {
        long declaredBytes = 1048576L; // 1 MiB
        ReserveMediaUploadRequest request =
                new ReserveMediaUploadRequest(
                        "illustration.png", "image/png", declaredBytes, activeSource.getId());

        long contentReservedBefore = getPoolReservedBytes(CONTENT_POOL_ID);
        long tempReservedBefore = getPoolReservedBytes(TEMP_POOL_ID);

        ReserveMediaUploadResult result =
                reservationService.reserveContentUpload(contentAdminUser.getId(), request);

        assertThat(result).isNotNull();
        assertThat(result.uploadRequestId()).isNotNull();
        assertThat(result.mediaId()).isNotNull();
        assertThat(result.tempBucket()).isNotBlank();
        // Server-side temp key does NOT append client filename
        assertThat(result.tempObjectKey()).isEqualTo("temp/" + result.uploadRequestId());
        assertThat(result.declaredSizeBytes()).isEqualTo(declaredBytes);
        assertThat(result.maxSizeBytes()).isEqualTo(declaredBytes);
        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.expiresAt()).isAfter(Instant.now());

        // Verify media_assets row: filename preserved for metadata, storage paths remain null
        Map<String, Object> mediaRow =
                jdbcTemplate.queryForMap(
                        "SELECT media_type, purpose, original_filename, mime_type, uploaded_by, status, source_id, bucket, object_key, file_size_bytes FROM media_assets WHERE id = ?",
                        result.mediaId());
        assertThat(mediaRow.get("media_type")).isEqualTo("IMAGE");
        assertThat(mediaRow.get("purpose")).isEqualTo("CONTENT");
        assertThat(mediaRow.get("original_filename")).isEqualTo("illustration.png");
        assertThat(mediaRow.get("mime_type")).isEqualTo("image/png");
        assertThat(mediaRow.get("uploaded_by")).isEqualTo(contentAdminUser.getId());
        assertThat(mediaRow.get("status")).isEqualTo("PENDING");
        assertThat(mediaRow.get("source_id")).isEqualTo(activeSource.getId());
        assertThat(mediaRow.get("bucket")).isNull();
        assertThat(mediaRow.get("object_key")).isNull();
        assertThat(mediaRow.get("file_size_bytes")).isNull();

        // Verify media_upload_requests row
        Map<String, Object> reqRow =
                jdbcTemplate.queryForMap(
                        "SELECT media_id, temp_bucket, temp_object_key, declared_size_bytes, declared_mime_type, max_size_bytes, status, expires_at FROM media_upload_requests WHERE id = ?",
                        result.uploadRequestId());
        assertThat(reqRow.get("media_id")).isEqualTo(result.mediaId());
        assertThat(reqRow.get("temp_bucket")).isEqualTo(result.tempBucket());
        assertThat(reqRow.get("temp_object_key")).isEqualTo("temp/" + result.uploadRequestId());
        assertThat(((Number) reqRow.get("declared_size_bytes")).longValue())
                .isEqualTo(declaredBytes);
        assertThat(reqRow.get("declared_mime_type")).isEqualTo("image/png");
        assertThat(((Number) reqRow.get("max_size_bytes")).longValue()).isEqualTo(declaredBytes);
        assertThat(reqRow.get("status")).isEqualTo("PENDING");

        // Verify storage_allocations rows
        String contentAllocKey = "req:" + result.uploadRequestId() + ":content";
        String tempAllocKey = "req:" + result.uploadRequestId() + ":temp";

        verifyAllocation(
                contentAllocKey,
                CONTENT_POOL_ID,
                result.mediaId(),
                result.uploadRequestId(),
                declaredBytes);
        verifyAllocation(
                tempAllocKey,
                TEMP_POOL_ID,
                result.mediaId(),
                result.uploadRequestId(),
                declaredBytes);

        // Verify pool counter incremented
        assertThat(getPoolReservedBytes(CONTENT_POOL_ID))
                .isEqualTo(contentReservedBefore + declaredBytes);
        assertThat(getPoolReservedBytes(TEMP_POOL_ID))
                .isEqualTo(tempReservedBefore + declaredBytes);
    }

    @Test
    @DisplayName("AC10/AC11/AC24: Content admin successfully reserves audio upload up to 20 MiB")
    void contentAdminSuccessfullyReservesAudioUpload() {
        long declaredBytes = 15728640L; // 15 MiB
        ReserveMediaUploadRequest request =
                new ReserveMediaUploadRequest("listening.mp3", "audio/mpeg", declaredBytes, null);

        long contentReservedBefore = getPoolReservedBytes(CONTENT_POOL_ID);
        long tempReservedBefore = getPoolReservedBytes(TEMP_POOL_ID);

        ReserveMediaUploadResult result =
                reservationService.reserveContentUpload(contentAdminUser.getId(), request);

        assertThat(result).isNotNull();
        assertThat(result.tempObjectKey()).isEqualTo("temp/" + result.uploadRequestId());

        Map<String, Object> mediaRow =
                jdbcTemplate.queryForMap(
                        "SELECT media_type, status FROM media_assets WHERE id = ?",
                        result.mediaId());
        assertThat(mediaRow.get("media_type")).isEqualTo("AUDIO");
        assertThat(mediaRow.get("status")).isEqualTo("PENDING");

        assertThat(getPoolReservedBytes(CONTENT_POOL_ID))
                .isEqualTo(contentReservedBefore + declaredBytes);
        assertThat(getPoolReservedBytes(TEMP_POOL_ID))
                .isEqualTo(tempReservedBefore + declaredBytes);
    }

    @Test
    @DisplayName(
            "AC24: Unauthorized and inactive actors are rejected with ACCESS_DENIED and 0 mutations occur")
    void unauthorizedAndInactiveActorsAreRejected() {
        ReserveMediaUploadRequest request =
                new ReserveMediaUploadRequest("test.png", "image/png", 1024L, null);

        // 1. Learner user
        assertThatThrownBy(
                        () -> reservationService.reserveContentUpload(learnerUser.getId(), request))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.ACCESS_DENIED));

        // 2. System admin only (not content admin)
        assertThatThrownBy(
                        () ->
                                reservationService.reserveContentUpload(
                                        systemAdminUser.getId(), request))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.ACCESS_DENIED));

        // 3. Inactive/locked content admin
        assertThatThrownBy(
                        () ->
                                reservationService.reserveContentUpload(
                                        inactiveAdminUser.getId(), request))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.ACCESS_DENIED));

        // 4. Random non-existent user
        assertThatThrownBy(
                        () -> reservationService.reserveContentUpload(UUID.randomUUID(), request))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.ACCESS_DENIED));

        // 5. Null actor
        assertThatThrownBy(() -> reservationService.reserveContentUpload(null, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.UNAUTHENTICATED));

        // Verify zero mutations
        Integer uploadRequestsCount =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM media_upload_requests", Integer.class);
        Integer mediaAssetsCount =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM media_assets WHERE status = 'PENDING'",
                        Integer.class);
        assertThat(uploadRequestsCount).isEqualTo(0);
        assertThat(mediaAssetsCount).isEqualTo(0);
    }

    @Test
    @DisplayName(
            "AC24: Validation rules reject invalid filename, MIME with Locale.ROOT, size, and sourceId")
    void validationRejectsInvalidInputs() {
        UUID adminId = contentAdminUser.getId();

        // 1. Blank or null file name
        assertThatThrownBy(
                        () ->
                                reservationService.reserveContentUpload(
                                        adminId,
                                        new ReserveMediaUploadRequest(
                                                null, "image/png", 1024L, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_ERROR));

        assertThatThrownBy(
                        () ->
                                reservationService.reserveContentUpload(
                                        adminId,
                                        new ReserveMediaUploadRequest(
                                                "   ", "image/png", 1024L, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_ERROR));

        // 2. Filename > 255 chars
        String longName = "a".repeat(256) + ".png";
        assertThatThrownBy(
                        () ->
                                reservationService.reserveContentUpload(
                                        adminId,
                                        new ReserveMediaUploadRequest(
                                                longName, "image/png", 1024L, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_ERROR));

        // 3. Filename with control characters
        assertThatThrownBy(
                        () ->
                                reservationService.reserveContentUpload(
                                        adminId,
                                        new ReserveMediaUploadRequest(
                                                "bad\nname.png", "image/png", 1024L, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_ERROR));

        // 4. Filename with path traversal
        for (String traversal :
                List.of("../file.png", "folder/file.png", "folder\\file.png", "file..png")) {
            assertThatThrownBy(
                            () ->
                                    reservationService.reserveContentUpload(
                                            adminId,
                                            new ReserveMediaUploadRequest(
                                                    traversal, "image/png", 1024L, null)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(
                            ex ->
                                    assertThat(((BusinessException) ex).getErrorCode())
                                            .isEqualTo(ErrorCode.VALIDATION_ERROR));
        }

        // 5. Invalid MIME type (PDF, video, text)
        for (String invalidMime :
                List.of("application/pdf", "video/mp4", "text/plain", "image/gif")) {
            assertThatThrownBy(
                            () ->
                                    reservationService.reserveContentUpload(
                                            adminId,
                                            new ReserveMediaUploadRequest(
                                                    "file.ext", invalidMime, 1024L, null)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(
                            ex ->
                                    assertThat(((BusinessException) ex).getErrorCode())
                                            .isEqualTo(ErrorCode.VALIDATION_ERROR));
        }

        // 6. Non-positive declared size
        assertThatThrownBy(
                        () ->
                                reservationService.reserveContentUpload(
                                        adminId,
                                        new ReserveMediaUploadRequest(
                                                "file.png", "image/png", 0L, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_ERROR));

        assertThatThrownBy(
                        () ->
                                reservationService.reserveContentUpload(
                                        adminId,
                                        new ReserveMediaUploadRequest(
                                                "file.png", "image/png", -100L, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_ERROR));

        // 7. Image size exceeding 5 MiB (5,242,880 B)
        assertThatThrownBy(
                        () ->
                                reservationService.reserveContentUpload(
                                        adminId,
                                        new ReserveMediaUploadRequest(
                                                "big.png", "image/png", 5242881L, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE));

        // 8. Audio size exceeding 20 MiB (20,971,520 B)
        assertThatThrownBy(
                        () ->
                                reservationService.reserveContentUpload(
                                        adminId,
                                        new ReserveMediaUploadRequest(
                                                "big.mp3", "audio/mpeg", 20971521L, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE));

        // 9. Non-existent sourceId
        assertThatThrownBy(
                        () ->
                                reservationService.reserveContentUpload(
                                        adminId,
                                        new ReserveMediaUploadRequest(
                                                "file.png", "image/png", 1024L, UUID.randomUUID())))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));

        // 10. Archived sourceId (not available for publication)
        assertThatThrownBy(
                        () ->
                                reservationService.reserveContentUpload(
                                        adminId,
                                        new ReserveMediaUploadRequest(
                                                "file.png",
                                                "image/png",
                                                1024L,
                                                archivedSource.getId())))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    @DisplayName(
            "AC24: Slot limit enforces max 3 concurrent open upload requests per actor with CONFLICT")
    void slotLimitEnforcesMaxThreeOpenRequests() {
        UUID adminId = contentAdminUser.getId();

        // 1. Create 3 open requests successfully
        ReserveMediaUploadResult res1 =
                reservationService.reserveContentUpload(
                        adminId, new ReserveMediaUploadRequest("f1.png", "image/png", 1024L, null));
        ReserveMediaUploadResult res2 =
                reservationService.reserveContentUpload(
                        adminId, new ReserveMediaUploadRequest("f2.png", "image/png", 1024L, null));
        ReserveMediaUploadResult res3 =
                reservationService.reserveContentUpload(
                        adminId, new ReserveMediaUploadRequest("f3.png", "image/png", 1024L, null));

        assertThat(res1).isNotNull();
        assertThat(res2).isNotNull();
        assertThat(res3).isNotNull();

        // 2. 4th open request must fail with CONFLICT (409)
        assertThatThrownBy(
                        () ->
                                reservationService.reserveContentUpload(
                                        adminId,
                                        new ReserveMediaUploadRequest(
                                                "f4.png", "image/png", 1024L, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex -> {
                            BusinessException bEx = (BusinessException) ex;
                            assertThat(bEx.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
                            assertThat(bEx.getMessage()).contains("Maximum open upload requests");
                        });

        // 3. Verify exactly 3 upload requests exist in DB for this actor
        Integer count =
                jdbcTemplate.queryForObject(
                        "SELECT count(r) FROM media_upload_requests r JOIN media_assets m ON r.media_id = m.id WHERE m.uploaded_by = ?",
                        Integer.class,
                        adminId);
        assertThat(count).isEqualTo(3);
    }

    @Test
    @DisplayName(
            "AC24: Hourly limit window boundary allows requests after 60m and rejects within 60m")
    void hourlyLimitWindowBoundaryEnforced() {
        UUID adminId = contentAdminUser.getId();

        // 1. Seed 100 requests outside 60m window (61 minutes ago)
        transactionTemplate.execute(
                status -> {
                    for (int i = 0; i < 100; i++) {
                        UUID mId = UUID.randomUUID();
                        UUID rId = UUID.randomUUID();
                        jdbcTemplate.update(
                                "INSERT INTO media_assets (id, media_type, purpose, original_filename, uploaded_by, status, failure_code, created_at, updated_at) "
                                        + "VALUES (?, 'IMAGE', 'CONTENT', ?, ?, 'FAILED', 'TIMEOUT', NOW() - INTERVAL '61 minutes', NOW() - INTERVAL '61 minutes')",
                                mId,
                                "old-out-" + i + ".png",
                                adminId);
                        jdbcTemplate.update(
                                "INSERT INTO media_upload_requests (id, media_id, temp_bucket, temp_object_key, declared_size_bytes, declared_mime_type, max_size_bytes, status, expires_at, finished_at, failure_code, created_at, updated_at) "
                                        + "VALUES (?, ?, 'temp', ?, 1024, 'image/png', 1024, 'FAILED', NOW() - INTERVAL '55 minutes', NOW() - INTERVAL '55 minutes', 'TIMEOUT', NOW() - INTERVAL '61 minutes', NOW() - INTERVAL '61 minutes')",
                                rId,
                                mId,
                                "temp/old-out-" + i);
                    }
                    return null;
                });

        // Request succeeds because previous 100 are outside the 60m rolling window
        ReserveMediaUploadResult resAllowed =
                reservationService.reserveContentUpload(
                        adminId,
                        new ReserveMediaUploadRequest("allowed.png", "image/png", 1024L, null));
        assertThat(resAllowed).isNotNull();

        // 2. Now seed 100 requests strictly INSIDE 60m window (10 minutes ago)
        transactionTemplate.execute(
                status -> {
                    for (int i = 0; i < 100; i++) {
                        UUID mId = UUID.randomUUID();
                        UUID rId = UUID.randomUUID();
                        jdbcTemplate.update(
                                "INSERT INTO media_assets (id, media_type, purpose, original_filename, uploaded_by, status, failure_code, created_at, updated_at) "
                                        + "VALUES (?, 'IMAGE', 'CONTENT', ?, ?, 'FAILED', 'TIMEOUT', NOW() - INTERVAL '10 minutes', NOW() - INTERVAL '10 minutes')",
                                mId,
                                "old-in-" + i + ".png",
                                adminId);
                        jdbcTemplate.update(
                                "INSERT INTO media_upload_requests (id, media_id, temp_bucket, temp_object_key, declared_size_bytes, declared_mime_type, max_size_bytes, status, expires_at, finished_at, failure_code, created_at, updated_at) "
                                        + "VALUES (?, ?, 'temp', ?, 1024, 'image/png', 1024, 'FAILED', NOW() - INTERVAL '5 minutes', NOW() - INTERVAL '5 minutes', 'TIMEOUT', NOW() - INTERVAL '10 minutes', NOW() - INTERVAL '10 minutes')",
                                rId,
                                mId,
                                "temp/old-in-" + i);
                    }
                    return null;
                });

        // Next request must fail with RATE_LIMITED (429)
        assertThatThrownBy(
                        () ->
                                reservationService.reserveContentUpload(
                                        adminId,
                                        new ReserveMediaUploadRequest(
                                                "blocked.png", "image/png", 1024L, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex -> {
                            BusinessException bEx = (BusinessException) ex;
                            assertThat(bEx.getErrorCode()).isEqualTo(ErrorCode.RATE_LIMITED);
                            assertThat(bEx.getMessage()).contains("Hourly upload request limit");
                        });
    }

    @Test
    @DisplayName(
            "AC26/AC27: Quota exact fit succeeds and over-by-one rolls back both pools cleanly")
    void quotaExactFitAndOverByOneRollbackTwoPoolsCleanly() {
        UUID adminId = contentAdminUser.getId();

        long tempQuota = 1073741824L; // 1 GiB
        long currentReserved = getPoolReservedBytes(TEMP_POOL_ID);
        long currentUsed = getPoolUsedBytes(TEMP_POOL_ID);
        long available = tempQuota - currentReserved - currentUsed;

        // Leave exactly 1048576 bytes (1 MiB) available in TEMP pool
        long fillBytes = available - 1048576L;
        UUID fillerMediaId = UUID.randomUUID();
        UUID fillerAllocId = UUID.randomUUID();

        if (fillBytes > 0) {
            transactionTemplate.execute(
                    status -> {
                        jdbcTemplate.update(
                                "INSERT INTO media_assets (id, media_type, purpose, original_filename, uploaded_by, status) "
                                        + "VALUES (?, 'IMAGE', 'CONTENT', 'filler.png', ?, 'PENDING')",
                                fillerMediaId,
                                adminId);
                        jdbcTemplate.update(
                                "INSERT INTO storage_allocations (id, pool_id, allocation_key, media_id, bytes, state) "
                                        + "VALUES (?, ?, 'filler-temp-alloc', ?, ?, 'RESERVED')",
                                fillerAllocId,
                                TEMP_POOL_ID,
                                fillerMediaId,
                                fillBytes);
                        return null;
                    });
        }

        // Case A: Over by one byte (1048577 bytes > 1048576 available)
        long contentReservedBefore = getPoolReservedBytes(CONTENT_POOL_ID);
        long tempReservedBefore = getPoolReservedBytes(TEMP_POOL_ID);
        long initialMediaCount =
                jdbcTemplate.queryForObject("SELECT count(*) FROM media_assets", Long.class);
        long initialUploadCount =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM media_upload_requests", Long.class);
        long initialAllocCount =
                jdbcTemplate.queryForObject("SELECT count(*) FROM storage_allocations", Long.class);

        assertThatThrownBy(
                        () ->
                                reservationService.reserveContentUpload(
                                        adminId,
                                        new ReserveMediaUploadRequest(
                                                "over-one.png", "image/png", 1048577L, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex -> {
                            BusinessException bEx = (BusinessException) ex;
                            assertThat(bEx.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
                            assertThat(bEx.getMessage()).contains("Storage pool quota exceeded");
                        });

        // Preflight under pool lock blocks over-by-one: CONTENT and TEMP counters completely
        // unchanged, zero orphan rows
        assertThat(getPoolReservedBytes(CONTENT_POOL_ID)).isEqualTo(contentReservedBefore);
        assertThat(getPoolReservedBytes(TEMP_POOL_ID)).isEqualTo(tempReservedBefore);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM media_assets", Long.class))
                .isEqualTo(initialMediaCount);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM media_upload_requests", Long.class))
                .isEqualTo(initialUploadCount);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM storage_allocations", Long.class))
                .isEqualTo(initialAllocCount);

        // Case B: Exact fit (1048576 bytes == 1048576 available)
        ReserveMediaUploadResult exactResult =
                reservationService.reserveContentUpload(
                        adminId,
                        new ReserveMediaUploadRequest(
                                "exact-fit.png", "image/png", 1048576L, null));
        assertThat(exactResult).isNotNull();

        // Both pools reserved exactly 1048576 bytes
        assertThat(getPoolReservedBytes(CONTENT_POOL_ID))
                .isEqualTo(contentReservedBefore + 1048576L);
        assertThat(getPoolReservedBytes(TEMP_POOL_ID)).isEqualTo(tempReservedBefore + 1048576L);
        assertThat(getPoolReservedBytes(TEMP_POOL_ID)).isEqualTo(tempQuota);
    }

    @Test
    @DisplayName(
            "AC24/AC27: Same actor competing for third open slot serializes with observable PostgreSQL lock")
    void sameActorCompetingForThirdOpenSlotSerializesWithObservableLock() throws Exception {
        UUID adminId = contentAdminUser.getId();

        // 1. Establish 2 open requests for this actor (only 1 slot remaining)
        reservationService.reserveContentUpload(
                adminId, new ReserveMediaUploadRequest("s1.png", "image/png", 1024L, null));
        reservationService.reserveContentUpload(
                adminId, new ReserveMediaUploadRequest("s2.png", "image/png", 1024L, null));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch lockAcquiredLatch = new CountDownLatch(1);
        CountDownLatch releaseLockLatch = new CountDownLatch(1);
        CountDownLatch waiterStartedLatch = new CountDownLatch(1);
        AtomicReference<Long> blockerPidRef = new AtomicReference<>();
        AtomicReference<Long> waiterPidRef = new AtomicReference<>();

        try {
            // Thread 1: Acquire exclusive FOR UPDATE on the actor's user row, then reserve 3rd slot
            Future<?> holderFuture =
                    executor.submit(
                            () -> {
                                transactionTemplate.execute(
                                        status -> {
                                            Long pid =
                                                    jdbcTemplate.queryForObject(
                                                            "SELECT pg_backend_pid()", Long.class);
                                            blockerPidRef.set(pid);
                                            // Lock actor row FOR UPDATE
                                            jdbcTemplate.queryForObject(
                                                    "SELECT id FROM users WHERE id = ? FOR UPDATE",
                                                    UUID.class,
                                                    adminId);
                                            lockAcquiredLatch.countDown();
                                            try {
                                                releaseLockLatch.await(5, TimeUnit.SECONDS);
                                            } catch (InterruptedException e) {
                                                Thread.currentThread().interrupt();
                                            }
                                            reservationService.reserveContentUpload(
                                                    adminId,
                                                    new ReserveMediaUploadRequest(
                                                            "slot3-winner.png",
                                                            "image/png",
                                                            1024L,
                                                            null));
                                            return null;
                                        });
                            });

            assertThat(lockAcquiredLatch.await(5, TimeUnit.SECONDS)).isTrue();
            Long blockerPid = blockerPidRef.get();
            assertThat(blockerPid).isNotNull();

            // Thread 2: Concurrently attempts to reserve for the same actor; gets blocked in
            // PostgreSQL by Thread 1 on the actor row lock
            Future<?> contenderFuture =
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
                                                return reservationService.reserveContentUpload(
                                                        adminId,
                                                        new ReserveMediaUploadRequest(
                                                                "slot3-loser.png",
                                                                "image/png",
                                                                1024L,
                                                                null));
                                            }));

            assertThat(waiterStartedLatch.await(5, TimeUnit.SECONDS)).isTrue();
            Long waiterPid = waiterPidRef.get();
            assertThat(waiterPid).isNotNull();

            // Observable verification: Prove Thread 2 (waiterPid) is genuinely blocked by Thread 1
            // (blockerPid) in pg_blocking_pids
            assertThat(awaitBlockedBy(waiterPid, blockerPid, 5))
                    .as("Thread 2 must be blocked by Thread 1")
                    .isTrue();
            assertThat(contenderFuture.isDone()).isFalse();

            // Release Thread 1 to win slot 3 and commit
            releaseLockLatch.countDown();
            holderFuture.get(5, TimeUnit.SECONDS);

            // Thread 2 unblocks, re-reads open request count (= 3), and must fail with CONFLICT
            ExecutionException ex =
                    org.junit.jupiter.api.Assertions.assertThrows(
                            ExecutionException.class,
                            () -> contenderFuture.get(5, TimeUnit.SECONDS));
            assertThat(ex.getCause()).isInstanceOf(BusinessException.class);
            BusinessException bEx = (BusinessException) ex.getCause();
            assertThat(bEx.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
            assertThat(bEx.getMessage()).contains("Maximum open upload requests");

            // Verify exactly 3 open requests exist in DB
            Integer openCount =
                    jdbcTemplate.queryForObject(
                            "SELECT count(r) FROM media_upload_requests r JOIN media_assets m ON r.media_id = m.id WHERE m.uploaded_by = ? AND r.status = 'PENDING'",
                            Integer.class,
                            adminId);
            assertThat(openCount).isEqualTo(3);
        } finally {
            releaseLockLatch.countDown();
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("AC27: Two actors competing for quota serialize safely with observable pool lock")
    void twoActorsCompetingForQuotaSerializeSafelyWithObservableLock() throws Exception {
        User secondAdmin =
                transactionTemplate.execute(
                        status -> {
                            Role adminRole =
                                    roleRepository.findByCode(RoleCode.CONTENT_ADMIN).orElseThrow();
                            User u =
                                    userRepository.save(
                                            new User(
                                                    "admin-quota-race-"
                                                            + UUID.randomUUID()
                                                            + "@example.com",
                                                    "$2a$12$D4YF1N6gL/x5U12345678u0w7W.zZ6t1m7U9y9X3mN.rLw5QkOe",
                                                    "QuotaSecondAdmin",
                                                    "User"));
                            u.addRole(adminRole);
                            u.markEmailVerified(Instant.now());
                            return userRepository.saveAndFlush(u);
                        });

        long tempQuota = 1073741824L; // 1 GiB
        long currentReserved = getPoolReservedBytes(TEMP_POOL_ID);
        long currentUsed = getPoolUsedBytes(TEMP_POOL_ID);
        long available = tempQuota - currentReserved - currentUsed;

        // Leave exactly 1048576 bytes (1 MiB) available in TEMP pool
        long fillBytes = available - 1048576L;
        UUID fillerMediaId = UUID.randomUUID();

        if (fillBytes > 0) {
            transactionTemplate.execute(
                    status -> {
                        jdbcTemplate.update(
                                "INSERT INTO media_assets (id, media_type, purpose, original_filename, uploaded_by, status) "
                                        + "VALUES (?, 'IMAGE', 'CONTENT', 'filler-quota-race.png', ?, 'PENDING')",
                                fillerMediaId,
                                contentAdminUser.getId());
                        jdbcTemplate.update(
                                "INSERT INTO storage_allocations (id, pool_id, allocation_key, media_id, bytes, state) "
                                        + "VALUES (?, ?, 'filler-race-temp-alloc', ?, ?, 'RESERVED')",
                                UUID.randomUUID(),
                                TEMP_POOL_ID,
                                fillerMediaId,
                                fillBytes);
                        return null;
                    });
        }

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch lockAcquiredLatch = new CountDownLatch(1);
        CountDownLatch releaseLockLatch = new CountDownLatch(1);
        CountDownLatch waiterStartedLatch = new CountDownLatch(1);
        AtomicReference<Long> blockerPidRef = new AtomicReference<>();
        AtomicReference<Long> waiterPidRef = new AtomicReference<>();

        try {
            // Thread 1 (Actor 1): Lock storage pools FOR UPDATE, then reserve 1 MiB
            Future<?> holderFuture =
                    executor.submit(
                            () -> {
                                transactionTemplate.execute(
                                        status -> {
                                            Long pid =
                                                    jdbcTemplate.queryForObject(
                                                            "SELECT pg_backend_pid()", Long.class);
                                            blockerPidRef.set(pid);
                                            jdbcTemplate.query(
                                                    "SELECT id FROM storage_pools WHERE id IN (?, ?) ORDER BY id FOR UPDATE",
                                                    rs -> {},
                                                    CONTENT_POOL_ID,
                                                    TEMP_POOL_ID);
                                            lockAcquiredLatch.countDown();
                                            try {
                                                releaseLockLatch.await(5, TimeUnit.SECONDS);
                                            } catch (InterruptedException e) {
                                                Thread.currentThread().interrupt();
                                            }
                                            reservationService.reserveContentUpload(
                                                    contentAdminUser.getId(),
                                                    new ReserveMediaUploadRequest(
                                                            "race-winner.png",
                                                            "image/png",
                                                            1048576L,
                                                            null));
                                            return null;
                                        });
                            });

            assertThat(lockAcquiredLatch.await(5, TimeUnit.SECONDS)).isTrue();
            Long blockerPid = blockerPidRef.get();
            assertThat(blockerPid).isNotNull();

            // Thread 2 (Actor 2): Concurrently attempts to reserve 1 MiB; gets blocked by Thread 1
            // on storage_pools
            Future<?> contenderFuture =
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
                                                return reservationService.reserveContentUpload(
                                                        secondAdmin.getId(),
                                                        new ReserveMediaUploadRequest(
                                                                "race-loser.png",
                                                                "image/png",
                                                                1048576L,
                                                                null));
                                            }));

            assertThat(waiterStartedLatch.await(5, TimeUnit.SECONDS)).isTrue();
            Long waiterPid = waiterPidRef.get();
            assertThat(waiterPid).isNotNull();

            // Observable verification: Prove Thread 2 (waiterPid) is genuinely blocked on pool lock
            // by Thread 1 (blockerPid)
            assertThat(awaitBlockedBy(waiterPid, blockerPid, 5))
                    .as("Thread 2 must be blocked by Thread 1 on storage_pools")
                    .isTrue();
            assertThat(contenderFuture.isDone()).isFalse();

            // Release Thread 1 to consume the final 1 MiB and commit
            releaseLockLatch.countDown();
            holderFuture.get(5, TimeUnit.SECONDS);

            // Thread 2 unblocks under pool lock, reads fresh committed pool state, preflight
            // detects
            // quota capacity exceeded, and fails with CONFLICT
            ExecutionException ex =
                    org.junit.jupiter.api.Assertions.assertThrows(
                            ExecutionException.class,
                            () -> contenderFuture.get(5, TimeUnit.SECONDS));
            assertThat(ex.getCause()).isInstanceOf(BusinessException.class);
            BusinessException bEx = (BusinessException) ex.getCause();
            assertThat(bEx.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
            assertThat(bEx.getMessage()).contains("Storage pool quota exceeded");

            // Verify TEMP pool reserved_bytes is exactly tempQuota (never exceeded)
            assertThat(getPoolReservedBytes(TEMP_POOL_ID)).isEqualTo(tempQuota);
        } finally {
            releaseLockLatch.countDown();
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName(
            "AC26/AC27: Sequential reservations within same persistence context detect quota depletion without stale cache")
    void samePersistenceContextSequentialReservationsDetectQuotaDepletionWithoutStaleCache() {
        UUID adminId = contentAdminUser.getId();

        long tempQuota = 1073741824L; // 1 GiB
        long currentReserved = getPoolReservedBytes(TEMP_POOL_ID);
        long currentUsed = getPoolUsedBytes(TEMP_POOL_ID);
        long available = tempQuota - currentReserved - currentUsed;

        // Leave exactly 1048576 bytes (1 MiB) available in TEMP pool
        long fillBytes = available - 1048576L;
        UUID fillerMediaId = UUID.randomUUID();
        UUID fillerAllocId = UUID.randomUUID();

        if (fillBytes > 0) {
            transactionTemplate.execute(
                    status -> {
                        jdbcTemplate.update(
                                "INSERT INTO media_assets (id, media_type, purpose, original_filename, uploaded_by, status) "
                                        + "VALUES (?, 'IMAGE', 'CONTENT', 'filler-seq.png', ?, 'PENDING')",
                                fillerMediaId,
                                adminId);
                        jdbcTemplate.update(
                                "INSERT INTO storage_allocations (id, pool_id, allocation_key, media_id, bytes, state) "
                                        + "VALUES (?, ?, 'filler-temp-seq-alloc', ?, ?, 'RESERVED')",
                                fillerAllocId,
                                TEMP_POOL_ID,
                                fillerMediaId,
                                fillBytes);
                        return null;
                    });
        }

        // Run sequential reservations inside a single outer transaction (same persistence context)
        transactionTemplate.execute(
                status -> {
                    // First reservation consumes the remaining 1 MiB exactly
                    ReserveMediaUploadResult res1 =
                            reservationService.reserveContentUpload(
                                    adminId,
                                    new ReserveMediaUploadRequest(
                                            "first-1mib.png", "image/png", 1048576L, null));
                    assertThat(res1).isNotNull();

                    // Second reservation attempts to allocate 1024 bytes in the SAME persistence
                    // context
                    assertThatThrownBy(
                                    () ->
                                            reservationService.reserveContentUpload(
                                                    adminId,
                                                    new ReserveMediaUploadRequest(
                                                            "second-1k.png",
                                                            "image/png",
                                                            1024L,
                                                            null)))
                            .isInstanceOf(BusinessException.class)
                            .satisfies(
                                    ex -> {
                                        BusinessException bEx = (BusinessException) ex;
                                        assertThat(bEx.getErrorCode())
                                                .isEqualTo(ErrorCode.CONFLICT);
                                        assertThat(bEx.getMessage())
                                                .contains("Storage pool quota exceeded");
                                    });

                    // Verify no request or allocation for second-1k.png was created
                    Integer secondMediaCount =
                            jdbcTemplate.queryForObject(
                                    "SELECT count(*) FROM media_assets WHERE original_filename = 'second-1k.png'",
                                    Integer.class);
                    assertThat(secondMediaCount).isEqualTo(0);

                    // Rollback outer transaction safely
                    status.setRollbackOnly();
                    return null;
                });

        // After rollback, verify all mutations from first reservation were rolled back safely
        Integer firstMediaCount =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM media_assets WHERE original_filename = 'first-1mib.png'",
                        Integer.class);
        assertThat(firstMediaCount).isEqualTo(0);
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

    private long getPoolReservedBytes(UUID poolId) {
        Long val =
                jdbcTemplate.queryForObject(
                        "SELECT reserved_bytes FROM storage_pools WHERE id = ?",
                        Long.class,
                        poolId);
        return val != null ? val : 0L;
    }

    private long getPoolUsedBytes(UUID poolId) {
        Long val =
                jdbcTemplate.queryForObject(
                        "SELECT used_bytes FROM storage_pools WHERE id = ?", Long.class, poolId);
        return val != null ? val : 0L;
    }

    private void verifyAllocation(
            String key,
            UUID expectedPoolId,
            UUID expectedMediaId,
            UUID expectedRequestId,
            long expectedBytes) {
        Map<String, Object> allocRow =
                jdbcTemplate.queryForMap(
                        "SELECT pool_id, media_id, upload_request_id, bytes, state FROM storage_allocations WHERE allocation_key = ?",
                        key);
        assertThat(allocRow.get("pool_id")).isEqualTo(expectedPoolId);
        assertThat(allocRow.get("media_id")).isEqualTo(expectedMediaId);
        assertThat(allocRow.get("upload_request_id")).isEqualTo(expectedRequestId);
        assertThat(((Number) allocRow.get("bytes")).longValue()).isEqualTo(expectedBytes);
        assertThat(allocRow.get("state")).isEqualTo("RESERVED");
    }
}
