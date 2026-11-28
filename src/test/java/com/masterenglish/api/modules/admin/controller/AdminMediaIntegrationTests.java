package com.masterenglish.api.modules.admin.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.masterenglish.api.TestcontainersConfiguration;
import com.masterenglish.api.common.security.RoleCode;
import com.masterenglish.api.common.storage.MediaStorage;
import com.masterenglish.api.infrastructure.security.UserPrincipal;
import com.masterenglish.api.modules.admin.dto.request.ReserveMediaUploadHttpRequest;
import com.masterenglish.api.modules.content.dto.response.DecodedMediaInfo;
import com.masterenglish.api.modules.content.entity.ContentSource;
import com.masterenglish.api.modules.content.repository.ContentSourceRepository;
import com.masterenglish.api.modules.content.repository.MediaAssetRepository;
import com.masterenglish.api.modules.content.repository.MediaUploadGrantRepository;
import com.masterenglish.api.modules.content.service.MediaDecoder;
import com.masterenglish.api.modules.content.service.impl.CompletedMediaCleanup;
import com.masterenglish.api.modules.identity.entity.Role;
import com.masterenglish.api.modules.identity.entity.User;
import com.masterenglish.api.modules.identity.repository.RoleRepository;
import com.masterenglish.api.modules.identity.repository.UserRepository;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@EnabledIf("isDockerAvailable")
class AdminMediaIntegrationTests {

    static boolean isDockerAvailable() {
        try {
            org.testcontainers.DockerClientFactory.instance().client();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private ContentSourceRepository sourceRepository;
    @Autowired private MediaAssetRepository mediaAssetRepository;
    @Autowired private MediaUploadGrantRepository grantRepository;
    @Autowired private CompletedMediaCleanup cleanup;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

    @MockitoBean private MediaStorage mediaStorage;
    @MockitoBean private MediaDecoder mediaDecoder;

    private MockMvc mockMvc;
    private User contentAdmin;
    private User otherAdmin;
    private User learner;
    private UserPrincipal contentAdminPrincipal;
    private UserPrincipal otherAdminPrincipal;
    private UserPrincipal learnerPrincipal;
    private ContentSource activeSource;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        reset(mediaStorage, mediaDecoder);
        when(mediaStorage.isAvailable()).thenReturn(true);
        when(mediaStorage.bucket()).thenReturn("test-media-bucket");

        cleanUp();

        transactionTemplate.execute(
                status -> {
                    Role adminRole =
                            roleRepository.findByCode(RoleCode.CONTENT_ADMIN).orElseThrow();
                    Role learnerRole = roleRepository.findByCode(RoleCode.LEARNER).orElseThrow();

                    contentAdmin =
                            new User(
                                    "admin.media-" + UUID.randomUUID() + "@example.com",
                                    "$2a$12$D4YF1N6gL/x5U12345678u0w7W.zZ6t1m7U9y9X3mN.rLw5QkOe",
                                    "Admin",
                                    "Media");
                    contentAdmin.addRole(adminRole);
                    contentAdmin.markEmailVerified(Instant.now());
                    contentAdmin = userRepository.saveAndFlush(contentAdmin);

                    otherAdmin =
                            new User(
                                    "other.media-" + UUID.randomUUID() + "@example.com",
                                    "$2a$12$D4YF1N6gL/x5U12345678u0w7W.zZ6t1m7U9y9X3mN.rLw5QkOe",
                                    "Other",
                                    "Media");
                    otherAdmin.addRole(adminRole);
                    otherAdmin.markEmailVerified(Instant.now());
                    otherAdmin = userRepository.saveAndFlush(otherAdmin);

                    learner =
                            new User(
                                    "learner.media-" + UUID.randomUUID() + "@example.com",
                                    "$2a$12$D4YF1N6gL/x5U12345678u0w7W.zZ6t1m7U9y9X3mN.rLw5QkOe",
                                    "Learner",
                                    "Media");
                    learner.addRole(learnerRole);
                    learner.markEmailVerified(Instant.now());
                    learner = userRepository.saveAndFlush(learner);

                    activeSource =
                            sourceRepository.save(
                                    new ContentSource(
                                            UUID.randomUUID(),
                                            "Media Test Source",
                                            "SELF_AUTHORED",
                                            "https://example.com/source",
                                            "MasterEnglish Media Team",
                                            "SELF_AUTHORED",
                                            "https://example.com/license",
                                            "Attribution Note",
                                            "Internal Permission",
                                            "ACTIVE",
                                            contentAdmin.getId()));
                    return null;
                });

        contentAdminPrincipal = UserPrincipal.create(contentAdmin);
        otherAdminPrincipal = UserPrincipal.create(otherAdmin);
        learnerPrincipal = UserPrincipal.create(learner);
    }

    private void cleanUp() {
        transactionTemplate.execute(
                status -> {
                    jdbcTemplate.update(
                            "UPDATE storage_allocations SET state = 'RELEASED', released_at = NOW() WHERE state <> 'RELEASED'");
                    jdbcTemplate.update("DELETE FROM storage_allocations WHERE state = 'RELEASED'");
                    jdbcTemplate.update("DELETE FROM media_upload_requests");
                    jdbcTemplate.update(
                            "DELETE FROM media_assets WHERE object_key IS NULL OR object_key LIKE 'content/%'");
                    jdbcTemplate.update(
                            "UPDATE storage_pools SET reserved_bytes = 0, used_bytes = 0");
                    return null;
                });
    }

    @Test
    @DisplayName("POST /upload-requests: Unauthenticated returns 401")
    void reserve_unauthenticated_returns401() throws Exception {
        ReserveMediaUploadHttpRequest request =
                new ReserveMediaUploadHttpRequest(
                        "audio.mp3", 1024L, 1024L, "audio/mpeg", activeSource.getId());

        mockMvc.perform(
                        post("/api/v1/admin/media/upload-requests")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /upload-requests: Non-admin (LEARNER) returns 403")
    void reserve_learnerRole_returns403() throws Exception {
        ReserveMediaUploadHttpRequest request =
                new ReserveMediaUploadHttpRequest(
                        "audio.mp3", 1024L, 1024L, "audio/mpeg", activeSource.getId());

        mockMvc.perform(
                        post("/api/v1/admin/media/upload-requests")
                                .with(csrf())
                                .with(user(learnerPrincipal))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /upload-requests: Size exceeding cap returns 413")
    void reserve_exceedingCap_returns413() throws Exception {
        // Image cap is 5 MiB = 5,242,880 B. Request 6 MiB.
        ReserveMediaUploadHttpRequest request =
                new ReserveMediaUploadHttpRequest(
                        "large.png",
                        6 * 1024 * 1024L,
                        6 * 1024 * 1024L,
                        "image/png",
                        activeSource.getId());

        mockMvc.perform(
                        post("/api/v1/admin/media/upload-requests")
                                .with(csrf())
                                .with(user(contentAdminPrincipal))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    @DisplayName("POST /upload-requests: Invalid MIME returns 400")
    void reserve_invalidMime_returns400() throws Exception {
        ReserveMediaUploadHttpRequest request =
                new ReserveMediaUploadHttpRequest(
                        "file.exe", 1024L, 1024L, "application/octet-stream", activeSource.getId());

        mockMvc.perform(
                        post("/api/v1/admin/media/upload-requests")
                                .with(csrf())
                                .with(user(contentAdminPrincipal))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName(
            "Complete Media Lifecycle: Reserve -> Stage -> Complete -> Get Status -> Get Asset -> Archive")
    void fullMediaUploadLifecycle_happyPath() throws Exception {
        byte[] payload = "valid-audio-content-for-mp3-test".getBytes(StandardCharsets.UTF_8);

        // 1. Reserve upload request
        ReserveMediaUploadHttpRequest reserveReq =
                new ReserveMediaUploadHttpRequest(
                        "test.mp3",
                        (long) payload.length,
                        (long) payload.length,
                        "audio/mpeg",
                        activeSource.getId());

        MvcResult reserveResult =
                mockMvc.perform(
                                post("/api/v1/admin/media/upload-requests")
                                        .with(csrf())
                                        .with(user(contentAdminPrincipal))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(reserveReq)))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.uploadRequestId").exists())
                        .andExpect(jsonPath("$.mediaId").exists())
                        .andExpect(jsonPath("$.uploadUrl").exists())
                        .andExpect(jsonPath("$.status").value("PENDING"))
                        .andReturn();

        String uploadReqIdStr =
                objectMapper
                        .readTree(reserveResult.getResponse().getContentAsString())
                        .get("uploadRequestId")
                        .asText();
        String mediaIdStr =
                objectMapper
                        .readTree(reserveResult.getResponse().getContentAsString())
                        .get("mediaId")
                        .asText();
        UUID uploadRequestId = UUID.fromString(uploadReqIdStr);
        UUID mediaId = UUID.fromString(mediaIdStr);

        // 2. Stage bytes via PUT
        mockMvc.perform(
                        put("/api/v1/admin/media/upload-requests/{id}/bytes", uploadRequestId)
                                .with(csrf())
                                .with(user(contentAdminPrincipal))
                                .contentType("audio/mpeg")
                                .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.sizeBytes").value(payload.length))
                .andExpect(jsonPath("$.sha256Checksum").exists());

        // Setup mock for decoder and exact byte verification on both staged and final objects
        when(mediaStorage.delete(any())).thenReturn(true);
        when(mediaStorage.openStream(any()))
                .thenAnswer(invocation -> new ByteArrayInputStream(payload));
        when(mediaDecoder.decode(any(), eq("audio/mpeg")))
                .thenReturn(new DecodedMediaInfo("audio/mpeg", "mp3", null, null, 2.5, 44100, 2));

        // 3. Complete upload
        mockMvc.perform(
                        post("/api/v1/admin/media/upload-requests/{id}/complete", uploadRequestId)
                                .with(csrf())
                                .with(user(contentAdminPrincipal))
                                .param("sync", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        org.assertj.core.api.Assertions.assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT state FROM storage_allocations WHERE allocation_key = ?",
                                String.class,
                                "req:" + uploadRequestId + ":temp"))
                .isEqualTo("RESERVED");
        org.mockito.Mockito.verify(mediaStorage, org.mockito.Mockito.never()).delete(any());

        // 4. Get upload request status
        mockMvc.perform(
                        get("/api/v1/admin/media/upload-requests/{id}", uploadRequestId)
                                .with(user(contentAdminPrincipal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.mediaId").value(mediaIdStr));

        // 5. Get media asset metadata
        mockMvc.perform(
                        get("/api/v1/admin/media/assets/{id}", mediaId)
                                .with(user(contentAdminPrincipal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(mediaIdStr))
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.mimeType").value("audio/mpeg"))
                .andExpect(jsonPath("$.durationMs").value(2500))
                .andExpect(jsonPath("$.bucket").doesNotExist()) // Never leak bucket
                .andExpect(jsonPath("$.objectKey").doesNotExist()); // Never leak objectKey

        // 6. Archive asset
        mockMvc.perform(
                        post("/api/v1/admin/media/assets/{id}/archive", mediaId)
                                .with(csrf())
                                .with(user(contentAdminPrincipal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"))
                .andExpect(jsonPath("$.archivedAt").exists());

        var grant = grantRepository.findById(uploadRequestId).orElseThrow();
        when(mediaStorage.bucket()).thenReturn(grant.getTempBucket());
        cleanup.runAt(grant.getFinishedAt().plusSeconds(86399));
        org.mockito.Mockito.verify(mediaStorage, org.mockito.Mockito.never()).delete(any());
        when(mediaStorage.delete(grant.getTempObjectKey())).thenReturn(false, true);
        when(mediaStorage.exists(grant.getTempObjectKey())).thenReturn(true);
        cleanup.runAt(grant.getFinishedAt().plusSeconds(86400));
        org.assertj.core.api.Assertions.assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT state FROM storage_allocations WHERE allocation_key = ?",
                                String.class,
                                "req:" + uploadRequestId + ":temp"))
                .isEqualTo("RESERVED");
        cleanup.runAt(grant.getFinishedAt().plusSeconds(86401));
        cleanup.runAt(grant.getFinishedAt().plusSeconds(86402));
        org.mockito.Mockito.verify(mediaStorage, org.mockito.Mockito.times(2))
                .delete(grant.getTempObjectKey());
        org.assertj.core.api.Assertions.assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT state FROM storage_allocations WHERE allocation_key = ?",
                                String.class,
                                "req:" + uploadRequestId + ":temp"))
                .isEqualTo("RELEASED");
    }

    @Test
    @DisplayName(
            "Cleanup transitions expired PENDING/VERIFYING grants and retains reservation until physical absence is proven")
    void cleanupRetainsReservationUntilPhysicalAbsenceIsProven() throws Exception {
        byte[] payload = "cleanup-test-audio".getBytes(StandardCharsets.UTF_8);

        ReserveMediaUploadHttpRequest req =
                new ReserveMediaUploadHttpRequest(
                        "expired.mp3",
                        (long) payload.length,
                        (long) payload.length,
                        "audio/mpeg",
                        activeSource.getId());

        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/admin/media/upload-requests")
                                        .with(csrf())
                                        .with(user(contentAdminPrincipal))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(req)))
                        .andExpect(status().isCreated())
                        .andReturn();

        var json = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID reqId = UUID.fromString(json.get("uploadRequestId").asText());
        var grant = grantRepository.findById(reqId).orElseThrow();
        when(mediaStorage.bucket()).thenReturn(grant.getTempBucket());

        // 1. Before expiry: remains PENDING, TEMP allocation RESERVED
        cleanup.runAt(grant.getExpiresAt().minusSeconds(1));
        org.assertj.core.api.Assertions.assertThat(
                        grantRepository.findById(reqId).orElseThrow().getStatus())
                .isEqualTo("PENDING");
        org.assertj.core.api.Assertions.assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT state FROM storage_allocations WHERE allocation_key = ?",
                                String.class,
                                "req:" + reqId + ":temp"))
                .isEqualTo("RESERVED");

        // 2. Past expiry: transitions PENDING -> EXPIRED, TEMP allocation still RESERVED until +24h
        Instant expiredAt = grant.getExpiresAt().plusSeconds(1);
        cleanup.runAt(expiredAt);
        var expiredGrant = grantRepository.findById(reqId).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(expiredGrant.getStatus()).isEqualTo("EXPIRED");
        org.assertj.core.api.Assertions.assertThat(
                        mediaAssetRepository
                                .findById(expiredGrant.getMediaId())
                                .orElseThrow()
                                .getStatus())
                .isEqualTo("FAILED");
        org.assertj.core.api.Assertions.assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT state FROM storage_allocations WHERE allocation_key = ?",
                                String.class,
                                "req:" + reqId + ":temp"))
                .isEqualTo("RESERVED");

        // 3. At 23h59m59s after finishedAt: still retained
        cleanup.runAt(expiredGrant.getFinishedAt().plusSeconds(86399));
        org.mockito.Mockito.verify(mediaStorage, org.mockito.Mockito.never())
                .delete(expiredGrant.getTempObjectKey());

        // 4. At 24h: storage delete returns false and exists returns true -> retained
        when(mediaStorage.delete(expiredGrant.getTempObjectKey())).thenReturn(false);
        when(mediaStorage.exists(expiredGrant.getTempObjectKey())).thenReturn(true);
        cleanup.runAt(expiredGrant.getFinishedAt().plusSeconds(86400));
        org.assertj.core.api.Assertions.assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT state FROM storage_allocations WHERE allocation_key = ?",
                                String.class,
                                "req:" + reqId + ":temp"))
                .isEqualTo("RESERVED");

        // 5. Wrong bucket -> retained
        when(mediaStorage.bucket()).thenReturn("different-bucket");
        cleanup.runAt(expiredGrant.getFinishedAt().plusSeconds(86401));
        org.assertj.core.api.Assertions.assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT state FROM storage_allocations WHERE allocation_key = ?",
                                String.class,
                                "req:" + reqId + ":temp"))
                .isEqualTo("RESERVED");

        // 6. Matching bucket and physical absence proven -> released once
        when(mediaStorage.bucket()).thenReturn(expiredGrant.getTempBucket());
        when(mediaStorage.delete(expiredGrant.getTempObjectKey())).thenReturn(true);
        cleanup.runAt(expiredGrant.getFinishedAt().plusSeconds(86402));
        org.assertj.core.api.Assertions.assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT state FROM storage_allocations WHERE allocation_key = ?",
                                String.class,
                                "req:" + reqId + ":temp"))
                .isEqualTo("RELEASED");
    }

    @Test
    void cleanupKeepsTempReservationWhenStagingWriteMayStillRun() throws Exception {
        var request =
                new ReserveMediaUploadHttpRequest(
                        "pending-write.mp3", 16L, 16L, "audio/mpeg", activeSource.getId());
        var result =
                mockMvc.perform(
                                post("/api/v1/admin/media/upload-requests")
                                        .with(csrf())
                                        .with(user(contentAdminPrincipal))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(request)))
                        .andExpect(status().isCreated())
                        .andReturn();
        UUID id =
                UUID.fromString(
                        objectMapper
                                .readTree(result.getResponse().getContentAsString())
                                .get("uploadRequestId")
                                .asText());
        var grant = grantRepository.findById(id).orElseThrow();
        jdbcTemplate.update(
                "UPDATE media_upload_requests SET put_claim_token = ?, put_claimed_at = NOW() WHERE id = ?",
                UUID.randomUUID(),
                id);
        when(mediaStorage.bucket()).thenReturn(grant.getTempBucket());
        when(mediaStorage.delete(grant.getTempObjectKey())).thenReturn(true);
        Instant expiredAt = grant.getExpiresAt().plusSeconds(1);
        cleanup.runAt(expiredAt);
        cleanup.runAt(expiredAt.plusSeconds(86400));
        org.assertj.core.api.Assertions.assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT state FROM storage_allocations WHERE allocation_key = ?",
                                String.class,
                                "req:" + id + ":temp"))
                .isEqualTo("RESERVED");
        org.mockito.Mockito.verify(mediaStorage, org.mockito.Mockito.never())
                .delete(grant.getTempObjectKey());
    }

    @Test
    @DisplayName("Cross-owner access: other admin accessing upload request or asset returns 404")
    void crossOwner_returns404() throws Exception {
        byte[] payload = "private-audio".getBytes(StandardCharsets.UTF_8);

        ReserveMediaUploadHttpRequest reserveReq =
                new ReserveMediaUploadHttpRequest(
                        "test.mp3",
                        (long) payload.length,
                        (long) payload.length,
                        "audio/mpeg",
                        activeSource.getId());

        MvcResult reserveResult =
                mockMvc.perform(
                                post("/api/v1/admin/media/upload-requests")
                                        .with(csrf())
                                        .with(user(contentAdminPrincipal))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(reserveReq)))
                        .andExpect(status().isCreated())
                        .andReturn();

        String uploadReqIdStr =
                objectMapper
                        .readTree(reserveResult.getResponse().getContentAsString())
                        .get("uploadRequestId")
                        .asText();
        String mediaIdStr =
                objectMapper
                        .readTree(reserveResult.getResponse().getContentAsString())
                        .get("mediaId")
                        .asText();

        // Other admin attempts to view status -> 404
        mockMvc.perform(
                        get("/api/v1/admin/media/upload-requests/{id}", uploadReqIdStr)
                                .with(user(otherAdminPrincipal)))
                .andExpect(status().isNotFound());

        // Other admin attempts to view asset -> 404
        mockMvc.perform(
                        get("/api/v1/admin/media/assets/{id}", mediaIdStr)
                                .with(user(otherAdminPrincipal)))
                .andExpect(status().isNotFound());

        // Other admin attempts to archive asset -> 404
        mockMvc.perform(
                        post("/api/v1/admin/media/assets/{id}/archive", mediaIdStr)
                                .with(csrf())
                                .with(user(otherAdminPrincipal)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Storage disabled returns 503 SERVICE_UNAVAILABLE")
    void storageDisabled_returns503() throws Exception {
        when(mediaStorage.isAvailable()).thenReturn(false);

        ReserveMediaUploadHttpRequest reserveReq =
                new ReserveMediaUploadHttpRequest(
                        "test.mp3", 100L, 100L, "audio/mpeg", activeSource.getId());

        // Staging with disabled storage
        mockMvc.perform(
                        put("/api/v1/admin/media/upload-requests/{id}/bytes", UUID.randomUUID())
                                .with(csrf())
                                .with(user(contentAdminPrincipal))
                                .contentType("audio/mpeg")
                                .content("sample-data".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isServiceUnavailable());
    }
}
