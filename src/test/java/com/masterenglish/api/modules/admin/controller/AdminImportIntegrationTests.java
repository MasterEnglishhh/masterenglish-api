package com.masterenglish.api.modules.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.masterenglish.api.TestcontainersConfiguration;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.security.RoleCode;
import com.masterenglish.api.common.storage.MediaStorage;
import com.masterenglish.api.infrastructure.security.UserPrincipal;
import com.masterenglish.api.modules.content.entity.ContentItem;
import com.masterenglish.api.modules.content.entity.ContentRevision;
import com.masterenglish.api.modules.content.entity.ContentSource;
import com.masterenglish.api.modules.content.entity.StorageAllocation;
import com.masterenglish.api.modules.content.repository.ContentItemRepository;
import com.masterenglish.api.modules.content.repository.ContentRevisionRepository;
import com.masterenglish.api.modules.content.repository.ContentSourceRepository;
import com.masterenglish.api.modules.content.repository.ImportJobRepository;
import com.masterenglish.api.modules.content.repository.ImportJobRowRepository;
import com.masterenglish.api.modules.content.repository.StorageAllocationRepository;
import com.masterenglish.api.modules.content.service.impl.ImportJobTxService;
import com.masterenglish.api.modules.identity.entity.Role;
import com.masterenglish.api.modules.identity.entity.User;
import com.masterenglish.api.modules.identity.repository.RoleRepository;
import com.masterenglish.api.modules.identity.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@EnabledIf("isDockerAvailable")
class AdminImportIntegrationTests {

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
    @Autowired private ContentItemRepository itemRepository;
    @Autowired private ContentRevisionRepository revisionRepository;
    @Autowired private ImportJobRepository jobRepository;
    @Autowired private ImportJobRowRepository rowRepository;
    @Autowired private ImportJobTxService importTxService;
    @Autowired private StorageAllocationRepository allocationRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @MockitoBean private MediaStorage mediaStorage;

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
        ImportStorageFixture.stub(mediaStorage);
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        cleanUp();

        transactionTemplate.execute(
                status -> {
                    Role adminRole =
                            roleRepository.findByCode(RoleCode.CONTENT_ADMIN).orElseThrow();
                    Role learnerRole = roleRepository.findByCode(RoleCode.LEARNER).orElseThrow();

                    contentAdmin =
                            new User(
                                    "admin.import-" + UUID.randomUUID() + "@example.com",
                                    "$2a$12$D4YF1N6gL/x5U12345678u0w7W.zZ6t1m7U9y9X3mN.rLw5QkOe",
                                    "Admin",
                                    "Import");
                    contentAdmin.addRole(adminRole);
                    contentAdmin.markEmailVerified(Instant.now());
                    contentAdmin = userRepository.saveAndFlush(contentAdmin);

                    otherAdmin =
                            new User(
                                    "other.import-" + UUID.randomUUID() + "@example.com",
                                    "$2a$12$D4YF1N6gL/x5U12345678u0w7W.zZ6t1m7U9y9X3mN.rLw5QkOe",
                                    "Other",
                                    "Import");
                    otherAdmin.addRole(adminRole);
                    otherAdmin.markEmailVerified(Instant.now());
                    otherAdmin = userRepository.saveAndFlush(otherAdmin);

                    learner =
                            new User(
                                    "learner.import-" + UUID.randomUUID() + "@example.com",
                                    "$2a$12$D4YF1N6gL/x5U12345678u0w7W.zZ6t1m7U9y9X3mN.rLw5QkOe",
                                    "Learner",
                                    "User");
                    learner.addRole(learnerRole);
                    learner.markEmailVerified(Instant.now());
                    learner = userRepository.saveAndFlush(learner);

                    activeSource =
                            sourceRepository.save(
                                    new ContentSource(
                                            UUID.randomUUID(),
                                            "Oxford Test Dictionary",
                                            "EXTERNAL",
                                            "https://oxford.example.com",
                                            "Oxford Press",
                                            "CC-BY-4.0",
                                            null,
                                            "Attribution text",
                                            null,
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
                    jdbcTemplate.update("DELETE FROM import_job_rows");
                    jdbcTemplate.update("DELETE FROM import_jobs");
                    jdbcTemplate.update("DELETE FROM revision_media_refs");
                    jdbcTemplate.update("DELETE FROM content_source_links");
                    jdbcTemplate.update("DELETE FROM content_revisions");
                    jdbcTemplate.update("DELETE FROM content_items");
                    jdbcTemplate.update("DELETE FROM content_sources");
                    return null;
                });
    }

    @Test
    @DisplayName("AC24: Anonymous bị từ chối 401 khi tạo import job")
    void anonymousRequestReturns401() throws Exception {
        MockMultipartFile file =
                new MockMultipartFile(
                        "file",
                        "test.csv",
                        "text/csv",
                        "lemma,part_of_speech,cefr_level,meaning_vi\nrun,VERB,A1,chạy"
                                .getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(
                        multipart("/api/v1/admin/import/jobs")
                                .file(file)
                                .param("module", "VOCABULARY")
                                .param("requestId", UUID.randomUUID().toString())
                                .param("namespace", "oxford")
                                .with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("AC24: Learner bị từ chối 403 khi gọi API import")
    void learnerRoleReturns403() throws Exception {
        MockMultipartFile file =
                new MockMultipartFile(
                        "file",
                        "test.csv",
                        "text/csv",
                        "lemma,part_of_speech,cefr_level,meaning_vi\nrun,VERB,A1,chạy"
                                .getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(
                        multipart("/api/v1/admin/import/jobs")
                                .file(file)
                                .param("module", "VOCABULARY")
                                .param("requestId", UUID.randomUUID().toString())
                                .param("namespace", "oxford")
                                .with(user(learnerPrincipal))
                                .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("AC14/27: File vượt 5 MiB bị từ chối 413 Payload Too Large")
    void fileOver5MiBReturns413() throws Exception {
        byte[] bigContent = new byte[5 * 1024 * 1024 + 1];
        MockMultipartFile file = new MockMultipartFile("file", "big.csv", "text/csv", bigContent);

        mockMvc.perform(
                        multipart("/api/v1/admin/import/jobs")
                                .file(file)
                                .param("module", "VOCABULARY")
                                .param("requestId", UUID.randomUUID().toString())
                                .param("namespace", "oxford")
                                .with(user(contentAdminPrincipal))
                                .with(csrf()))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    @DisplayName("AC14: Thiếu cột bắt buộc trong header trả về 400 Bad Request")
    void missingRequiredHeaderReturns400() throws Exception {
        String csv = "external_key,lemma,meaning_vi\nk1,run,chay";
        MockMultipartFile file =
                new MockMultipartFile(
                        "file", "invalid.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(
                        multipart("/api/v1/admin/import/jobs")
                                .file(file)
                                .param("module", "VOCABULARY")
                                .param("requestId", UUID.randomUUID().toString())
                                .param("namespace", "oxford")
                                .with(user(contentAdminPrincipal))
                                .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.detail")
                                .value(
                                        org.hamcrest.Matchers.containsString(
                                                "Missing required CSV column")));
    }

    @Test
    @DisplayName(
            "AC14/16/17/27: Upload CSV chạy DryRun, tạo job READY, điền VALID/INVALID, và lưu allocation RESERVED")
    void uploadWordCsvDryRunCreatesJobAndRows() throws Exception {
        org.mockito.Mockito.when(mediaStorage.bucket()).thenReturn("actual-import-bucket");
        UUID requestId = UUID.randomUUID();
        // 1 row valid, 1 row invalid (bad POS and bad CEFR), BOM UTF-8
        String csv =
                "\uFEFFexternal_key,lemma,ipa,part_of_speech,cefr_level,meaning_vi,definition_en,example_en,example_vi,source_id\n"
                        + "w1,abandon,/əˈbændən/,VERB,B2,từ bỏ,to leave behind,He abandoned his car.,Anh ấy bỏ xe lại.,"
                        + activeSource.getId()
                        + "\n"
                        + "w2,invalid_word,,INVALID_POS,Z99,,def,,,";

        MockMultipartFile file =
                new MockMultipartFile(
                        "file", "words.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        MvcResult result =
                mockMvc.perform(
                                multipart("/api/v1/admin/import/jobs")
                                        .file(file)
                                        .param("module", "VOCABULARY")
                                        .param("requestId", requestId.toString())
                                        .param("namespace", "oxford")
                                        .with(user(contentAdminPrincipal))
                                        .with(csrf()))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.status").value("READY"))
                        .andExpect(jsonPath("$.totalRows").value(2))
                        .andExpect(jsonPath("$.failedRows").value(1))
                        .andExpect(jsonPath("$.claimToken").doesNotHaveJsonPath())
                        .andReturn();

        String resBody = result.getResponse().getContentAsString();
        UUID jobId = UUID.fromString(objectMapper.readTree(resBody).get("id").asText());

        // Verify storage allocation is RESERVED
        StorageAllocation alloc =
                allocationRepository.findByAllocationKey("import:" + jobId).orElseThrow();
        assertThat(alloc.getState()).isEqualTo("RESERVED");
        assertThat(alloc.getImportJobId()).isEqualTo(jobId);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT input_bucket FROM import_jobs WHERE id = ?",
                                String.class,
                                jobId))
                .isEqualTo("actual-import-bucket");
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT bucket FROM storage_allocations WHERE import_job_id = ?",
                                String.class,
                                jobId))
                .isEqualTo("actual-import-bucket");

        // Verify rows: row 1 VALID, row 2 INVALID
        mockMvc.perform(
                        get("/api/v1/admin/import/jobs/" + jobId + "/rows")
                                .with(user(contentAdminPrincipal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.items[0].rowNumber").value(1))
                .andExpect(jsonPath("$.items[0].status").value("VALID"))
                .andExpect(jsonPath("$.items[0].externalKey").value("w1"))
                .andExpect(jsonPath("$.items[0].payload").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.items[0].resultSnapshot").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.items[1].rowNumber").value(2))
                .andExpect(jsonPath("$.items[1].status").value("INVALID"))
                .andExpect(
                        jsonPath("$.items[1].errors")
                                .value(org.hamcrest.Matchers.containsString("partOfSpeech")));

        // Replay with exact same requestId and file -> 200 OK
        mockMvc.perform(
                        multipart("/api/v1/admin/import/jobs")
                                .file(file)
                                .param("module", "VOCABULARY")
                                .param("requestId", requestId.toString())
                                .param("namespace", "oxford")
                                .with(user(contentAdminPrincipal))
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(jobId.toString()));

        // Replay with same requestId but different file -> 409 Conflict
        MockMultipartFile diffFile =
                new MockMultipartFile(
                        "file",
                        "words.csv",
                        "text/csv",
                        "external_key,lemma,ipa,part_of_speech,cefr_level,meaning_vi\nw3,cat,/kæt/,NOUN,A1,con mèo"
                                .getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(
                        multipart("/api/v1/admin/import/jobs")
                                .file(diffFile)
                                .param("module", "VOCABULARY")
                                .param("requestId", requestId.toString())
                                .param("namespace", "oxford")
                                .with(user(contentAdminPrincipal))
                                .with(csrf()))
                .andExpect(status().isConflict());

        // Cross-owner access returns 404
        mockMvc.perform(get("/api/v1/admin/import/jobs/" + jobId).with(user(otherAdminPrincipal)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName(
            "AC14/17/27: Confirm tạo Draft, kết thúc PARTIAL_SUCCESS và giữ quota khi chưa xóa input")
    void confirmJobProcessesValidRows() throws Exception {
        UUID requestId = UUID.randomUUID();
        String csv =
                "external_key,lemma,ipa,part_of_speech,cefr_level,meaning_vi,definition_en,example_en,example_vi,source_id\n"
                        + "word-01,brilliant,/ˈbrɪliənt/,ADJECTIVE,B1,xuất sắc,exceptionally clever or talented,A brilliant student.,Học sinh xuất sắc.,"
                        + activeSource.getId()
                        + "\n"
                        + "word-02,bad_row,,NOUN,X99,,def,,,";

        MockMultipartFile file =
                new MockMultipartFile(
                        "file", "words.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        MvcResult result =
                mockMvc.perform(
                                multipart("/api/v1/admin/import/jobs")
                                        .file(file)
                                        .param("module", "VOCABULARY")
                                        .param("requestId", requestId.toString())
                                        .param("namespace", "test-ns")
                                        .with(user(contentAdminPrincipal))
                                        .with(csrf()))
                        .andExpect(status().isCreated())
                        .andReturn();

        UUID jobId =
                UUID.fromString(
                        objectMapper
                                .readTree(result.getResponse().getContentAsString())
                                .get("id")
                                .asText());

        // Confirm the job
        mockMvc.perform(
                        post("/api/v1/admin/import/jobs/" + jobId + "/confirm")
                                .with(user(contentAdminPrincipal))
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARTIAL_SUCCESS"))
                .andExpect(jsonPath("$.totalRows").value(2))
                .andExpect(jsonPath("$.successRows").value(1))
                .andExpect(jsonPath("$.failedRows").value(1));

        // Verify ContentItem was created with Draft revision
        ContentItem item =
                itemRepository
                        .findByImportNamespaceAndExternalKey("test-ns", "word-01")
                        .orElseThrow();
        assertThat(item.getContentType()).isEqualTo("WORD");
        assertThat(item.getPublishedRevisionId()).isNull(); // Must NOT be auto-published!

        List<ContentRevision> revisions =
                revisionRepository.findByContentItemIdOrderByVersionNoDesc(item.getId());
        assertThat(revisions).hasSize(1);
        ContentRevision rev = revisions.get(0);
        assertThat(rev.getStatus()).isEqualTo("DRAFT");
        assertThat(rev.getVersionNo()).isEqualTo(1);
        assertThat(rev.getSnapshot()).contains("brilliant");

        StorageAllocation alloc =
                allocationRepository.findByAllocationKey("import:" + jobId).orElseThrow();
        assertThat(alloc.getState()).isEqualTo("RESERVED");
        assertThat(alloc.getReleasedAt()).isNull();

        // Check rows status: row 1 SUCCESS, row 2 FAILED
        mockMvc.perform(
                        get("/api/v1/admin/import/jobs/" + jobId + "/rows")
                                .with(user(contentAdminPrincipal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("SUCCESS"))
                .andExpect(
                        jsonPath("$.items[0].resultContentItemId").value(item.getId().toString()))
                .andExpect(jsonPath("$.items[1].status").value("INVALID"));
    }

    @Test
    void initialPutDoesNotReadyJobWhenReadBackDiffers() throws Exception {
        UUID requestId = UUID.randomUUID();
        String csv =
                "external_key,lemma,ipa,part_of_speech,cefr_level,meaning_vi,definition_en,example_en,example_vi,source_id\n"
                        + "w1,abandon,,VERB,B2,từ bỏ,to leave behind,,,"
                        + activeSource.getId();
        byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
        byte[] wrongBytes = bytes.clone();
        wrongBytes[0] ^= 1;
        org.mockito.Mockito.doAnswer(call -> new java.io.ByteArrayInputStream(wrongBytes))
                .when(mediaStorage)
                .openStream(org.mockito.ArgumentMatchers.anyString());

        mockMvc.perform(
                        multipart("/api/v1/admin/import/jobs")
                                .file(new MockMultipartFile("file", "words.csv", "text/csv", bytes))
                                .param("module", "VOCABULARY")
                                .param("requestId", requestId.toString())
                                .param("namespace", "oxford")
                                .with(user(contentAdminPrincipal))
                                .with(csrf()))
                .andExpect(status().isServiceUnavailable());

        var job =
                jobRepository
                        .findByCreatedByAndRequestId(contentAdmin.getId(), requestId)
                        .orElseThrow();
        assertThat(job.getStatus()).isEqualTo("UPLOADING");
        assertThat(
                        allocationRepository
                                .findByAllocationKey("import:" + job.getId())
                                .orElseThrow()
                                .getState())
                .isEqualTo("RESERVED");
    }

    @Test
    void ambiguousInputWriteKeepsDurableQuotaReservation() throws Exception {
        UUID requestId = UUID.randomUUID();
        byte[] bytes =
                ("external_key,lemma,ipa,part_of_speech,cefr_level,meaning_vi,definition_en,example_en,example_vi,source_id\n"
                                + "lost,brilliant,/ˈbrɪliənt/,ADJECTIVE,B1,xuất sắc,exceptionally clever,A brilliant student.,Học sinh xuất sắc.,"
                                + activeSource.getId())
                        .getBytes(StandardCharsets.UTF_8);
        org.mockito.Mockito.doThrow(
                        new com.masterenglish.api.common.storage.StorageException("lost response"))
                .doNothing()
                .when(mediaStorage)
                .putIfAbsent(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString());

        mockMvc.perform(
                        multipart("/api/v1/admin/import/jobs")
                                .file(new MockMultipartFile("file", "words.csv", "text/csv", bytes))
                                .param("module", "VOCABULARY")
                                .param("requestId", requestId.toString())
                                .with(user(contentAdminPrincipal))
                                .with(csrf()))
                .andExpect(status().is5xxServerError());

        var job =
                jobRepository
                        .findByCreatedByAndRequestId(contentAdmin.getId(), requestId)
                        .orElseThrow();
        assertThat(job.getStatus()).isEqualTo("UPLOADING");
        assertThat(
                        allocationRepository
                                .findByAllocationKey("import:" + job.getId())
                                .orElseThrow()
                                .getState())
                .isEqualTo("RESERVED");

        org.mockito.Mockito.doThrow(
                        new com.masterenglish.api.common.storage.StorageObjectNotFoundException(
                                "Input not stored", null))
                .doReturn(new java.io.ByteArrayInputStream(bytes))
                .when(mediaStorage)
                .openStream(job.getInputObjectKey());
        mockMvc.perform(
                        multipart("/api/v1/admin/import/jobs")
                                .file(new MockMultipartFile("file", "words.csv", "text/csv", bytes))
                                .param("module", "VOCABULARY")
                                .param("requestId", requestId.toString())
                                .with(user(contentAdminPrincipal))
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(job.getId().toString()))
                .andExpect(jsonPath("$.status").value("READY"));
    }

    @Test
    void staleConfirmationCannotFailRowAfterTakeover() throws Exception {
        String csv =
                "external_key,lemma,ipa,part_of_speech,cefr_level,meaning_vi,definition_en,example_en,example_vi,source_id\n"
                        + "takeover,brilliant,/ˈbrɪliənt/,ADJECTIVE,B1,xuất sắc,exceptionally clever,A brilliant student.,Học sinh xuất sắc.,"
                        + activeSource.getId();
        MvcResult result =
                mockMvc.perform(
                                multipart("/api/v1/admin/import/jobs")
                                        .file(
                                                new MockMultipartFile(
                                                        "file",
                                                        "words.csv",
                                                        "text/csv",
                                                        csv.getBytes(StandardCharsets.UTF_8)))
                                        .param("module", "VOCABULARY")
                                        .param("requestId", UUID.randomUUID().toString())
                                        .with(user(contentAdminPrincipal))
                                        .with(csrf()))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.status").value("READY"))
                        .andReturn();
        UUID jobId =
                UUID.fromString(
                        objectMapper
                                .readTree(result.getResponse().getContentAsString())
                                .path("id")
                                .asText());
        UUID oldClaim =
                importTxService.claimForConfirm(jobId, contentAdmin.getId()).getClaimToken();
        jdbcTemplate.update(
                "UPDATE import_jobs SET lease_until = NOW() - INTERVAL '1 second' WHERE id = ?",
                jobId);
        UUID newClaim =
                importTxService.claimForConfirm(jobId, contentAdmin.getId()).getClaimToken();
        assertThat(newClaim).isNotEqualTo(oldClaim);

        assertThatThrownBy(() -> importTxService.markRowFailed(jobId, oldClaim, 1, "[]"))
                .isInstanceOf(BusinessException.class);
        assertThat(rowRepository.findByJobIdAndRowNumber(jobId, 1).orElseThrow().getStatus())
                .isEqualTo("VALID");

        transactionTemplate.executeWithoutResult(
                status -> {
                    User owner = userRepository.findById(contentAdmin.getId()).orElseThrow();
                    owner.addRole(roleRepository.findByCode(RoleCode.LEARNER).orElseThrow());
                    owner.removeRole(RoleCode.CONTENT_ADMIN);
                });
        assertThatThrownBy(() -> importTxService.markRowFailed(jobId, newClaim, 1, "[]"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Active content administrator required");
        assertThatThrownBy(() -> importTxService.finalizeJob(jobId, newClaim, 0, 1, 0))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Active content administrator required");
        assertThat(jobRepository.findById(jobId).orElseThrow().getStatus()).isEqualTo("PROCESSING");
    }

    @Test
    @DisplayName("Upload CSV with malformed UTF-8 bytes returns 400 Bad Request")
    void uploadCsv_malformedUtf8_returns400() throws Exception {
        byte[] malformedBytes =
                new byte[] {(byte) 0xC3, (byte) 0x28, (byte) 0xA0}; // Invalid UTF-8 sequence
        MockMultipartFile file =
                new MockMultipartFile("file", "malformed.csv", "text/csv", malformedBytes);

        mockMvc.perform(
                        multipart("/api/v1/admin/import/jobs")
                                .file(file)
                                .param("module", "VOCABULARY")
                                .param("requestId", UUID.randomUUID().toString())
                                .param("namespace", "test-ns")
                                .with(csrf())
                                .with(user(contentAdminPrincipal)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Replay confirm trên terminal FAILED không tạo claim hoặc draft mới")
    void failedTerminalReplayDoesNotCreateNewDrafts() throws Exception {
        String csv =
                "external_key,lemma,ipa,part_of_speech,cefr_level,meaning_vi,definition_en,example_en,example_vi,source_id\n"
                        + "w-sql-fail,apple,/ˈæp.əl/,NOUN,A1,Quả táo,A fruit,I eat an apple.,Tôi ăn táo.,"
                        + activeSource.getId()
                        + "\n";
        MockMultipartFile file =
                new MockMultipartFile(
                        "file", "fail.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        MvcResult uploadRes =
                mockMvc.perform(
                                multipart("/api/v1/admin/import/jobs")
                                        .file(file)
                                        .param("module", "VOCABULARY")
                                        .param("requestId", UUID.randomUUID().toString())
                                        .param("namespace", "fail-ns")
                                        .with(csrf())
                                        .with(user(contentAdminPrincipal)))
                        .andExpect(status().isCreated())
                        .andReturn();

        UUID jobId =
                UUID.fromString(
                        objectMapper
                                .readTree(uploadRes.getResponse().getContentAsString())
                                .path("id")
                                .asText());

        // Corrupt source_id in row payload after dry-run upload so confirm fails deterministically
        String badPayload =
                jdbcTemplate
                        .queryForObject(
                                "SELECT payload::text FROM import_job_rows WHERE job_id = ?",
                                String.class,
                                jobId)
                        .replace(activeSource.getId().toString(), UUID.randomUUID().toString());
        jdbcTemplate.update(
                "UPDATE import_job_rows SET payload = CAST(? AS jsonb) WHERE job_id = ?",
                badPayload,
                jobId);

        long itemsBefore = itemRepository.count();

        mockMvc.perform(
                        post("/api/v1/admin/import/jobs/" + jobId + "/confirm")
                                .with(csrf())
                                .with(user(contentAdminPrincipal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failedRows").value(1))
                .andExpect(jsonPath("$.successRows").value(0));

        assertThat(itemRepository.count()).isEqualTo(itemsBefore);

        // Replay confirm on terminal FAILED
        mockMvc.perform(
                        post("/api/v1/admin/import/jobs/" + jobId + "/confirm")
                                .with(csrf())
                                .with(user(contentAdminPrincipal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failedRows").value(1))
                .andExpect(jsonPath("$.successRows").value(0));

        assertThat(itemRepository.count()).isEqualTo(itemsBefore);

        // Verify row is FAILED cleanly without leaking internal SQL details
        mockMvc.perform(
                        get("/api/v1/admin/import/jobs/" + jobId + "/rows")
                                .with(user(contentAdminPrincipal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("FAILED"))
                .andExpect(
                        jsonPath("$.items[0].errors")
                                .value(
                                        org.hamcrest.Matchers.not(
                                                org.hamcrest.Matchers.containsString(
                                                        "PSQLException"))));
    }

    @Test
    @DisplayName("CSV import: 5.000 dòng hợp lệ được nhận 201 và 5.001 dòng bị từ chối 400")
    void csvRecordLimit5000AcceptedAnd5001Rejected() throws Exception {
        String header =
                "external_key,lemma,ipa,part_of_speech,cefr_level,meaning_vi,definition_en,example_en,example_vi,source_id\n";
        StringBuilder sb5000 = new StringBuilder(header);
        for (int i = 1; i <= 5000; i++) {
            sb5000.append("w-")
                    .append(i)
                    .append(",word")
                    .append(i)
                    .append(",/w/,NOUN,B1,Nghĩa,Def,Ex,Ví dụ,")
                    .append(activeSource.getId())
                    .append("\n");
        }
        MockMultipartFile file5000 =
                new MockMultipartFile(
                        "file",
                        "5000.csv",
                        "text/csv",
                        sb5000.toString().getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(
                        multipart("/api/v1/admin/import/jobs")
                                .file(file5000)
                                .param("module", "VOCABULARY")
                                .param("requestId", UUID.randomUUID().toString())
                                .param("namespace", "limit-5000")
                                .with(csrf())
                                .with(user(contentAdminPrincipal)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalRows").value(5000));

        sb5000.append("w-5001,word5001,/w/,NOUN,B1,Nghĩa,Def,Ex,Ví dụ,")
                .append(activeSource.getId())
                .append("\n");
        MockMultipartFile file5001 =
                new MockMultipartFile(
                        "file",
                        "5001.csv",
                        "text/csv",
                        sb5000.toString().getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(
                        multipart("/api/v1/admin/import/jobs")
                                .file(file5001)
                                .param("module", "VOCABULARY")
                                .param("requestId", UUID.randomUUID().toString())
                                .param("namespace", "limit-5001")
                                .with(csrf())
                                .with(user(contentAdminPrincipal)))
                .andExpect(status().isBadRequest());
    }
}
