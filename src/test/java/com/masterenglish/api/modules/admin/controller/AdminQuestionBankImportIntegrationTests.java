package com.masterenglish.api.modules.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.masterenglish.api.modules.content.service.ContentAuthoringService;
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
class AdminQuestionBankImportIntegrationTests {

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
    @Autowired private StorageAllocationRepository allocationRepository;
    @Autowired private ContentAuthoringService authoringService;
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
                                    "admin.qb-" + UUID.randomUUID() + "@example.com",
                                    "$2a$12$D4YF1N6gL/x5U12345678u0w7W.zZ6t1m7U9y9X3mN.rLw5QkOe",
                                    "Admin",
                                    "QuestionBank");
                    contentAdmin.addRole(adminRole);
                    contentAdmin.markEmailVerified(Instant.now());
                    contentAdmin = userRepository.saveAndFlush(contentAdmin);

                    otherAdmin =
                            new User(
                                    "other.qb-" + UUID.randomUUID() + "@example.com",
                                    "$2a$12$D4YF1N6gL/x5U12345678u0w7W.zZ6t1m7U9y9X3mN.rLw5QkOe",
                                    "Other",
                                    "QuestionBank");
                    otherAdmin.addRole(adminRole);
                    otherAdmin.markEmailVerified(Instant.now());
                    otherAdmin = userRepository.saveAndFlush(otherAdmin);

                    learner =
                            new User(
                                    "learner.qb-" + UUID.randomUUID() + "@example.com",
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
                                            "ETS TOEIC Practice Test",
                                            "EXTERNAL",
                                            "https://ets.example.com",
                                            "Educational Testing Service",
                                            "Proprietary-License",
                                            null,
                                            "Attribution ETS",
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
                    jdbcTemplate.update("DELETE FROM group_materials");
                    jdbcTemplate.update("DELETE FROM set_items");
                    jdbcTemplate.update("DELETE FROM question_groups");
                    jdbcTemplate.update("DELETE FROM practice_sets");
                    jdbcTemplate.update("DELETE FROM questions");
                    jdbcTemplate.update("DELETE FROM revision_dependencies");
                    jdbcTemplate.update("DELETE FROM revision_media_refs");
                    jdbcTemplate.update("DELETE FROM content_source_links");
                    jdbcTemplate.update("DELETE FROM content_revisions");
                    jdbcTemplate.update("DELETE FROM content_items");
                    jdbcTemplate.update("DELETE FROM content_sources");
                    return null;
                });
    }

    @Test
    @DisplayName("AC15/24: Anonymous bị từ chối 401 khi tạo import job JSON")
    void anonymousRequestReturns401() throws Exception {
        MockMultipartFile file =
                new MockMultipartFile(
                        "file",
                        "test.json",
                        "application/json",
                        "{\"schema_version\":1,\"items\":[]}".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(
                        multipart("/api/v1/admin/import/jobs")
                                .file(file)
                                .param("module", "QUESTION_BANK")
                                .param("requestId", UUID.randomUUID().toString())
                                .param("namespace", "ets")
                                .with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("AC15/24: Learner bị từ chối 403 khi gọi API import JSON")
    void learnerRoleReturns403() throws Exception {
        MockMultipartFile file =
                new MockMultipartFile(
                        "file",
                        "test.json",
                        "application/json",
                        "{\"schema_version\":1,\"items\":[]}".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(
                        multipart("/api/v1/admin/import/jobs")
                                .file(file)
                                .param("module", "QUESTION_BANK")
                                .param("requestId", UUID.randomUUID().toString())
                                .param("namespace", "ets")
                                .with(user(learnerPrincipal))
                                .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("AC15: File JSON vượt 5 MiB bị từ chối 413 Payload Too Large")
    void fileOver5MiBReturns413() throws Exception {
        byte[] bigContent = new byte[5 * 1024 * 1024 + 1];
        MockMultipartFile file =
                new MockMultipartFile("file", "big.json", "application/json", bigContent);

        mockMvc.perform(
                        multipart("/api/v1/admin/import/jobs")
                                .file(file)
                                .param("module", "QUESTION_BANK")
                                .param("requestId", UUID.randomUUID().toString())
                                .param("namespace", "ets")
                                .with(user(contentAdminPrincipal))
                                .with(csrf()))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    @DisplayName("AC15: JSON không hợp lệ cú pháp hoặc cấu trúc bị từ chối 400 Bad Request")
    void malformedJsonReturns400() throws Exception {
        String badJson = "{ invalid-json }";
        MockMultipartFile file =
                new MockMultipartFile(
                        "file",
                        "bad.json",
                        "application/json",
                        badJson.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(
                        multipart("/api/v1/admin/import/jobs")
                                .file(file)
                                .param("module", "QUESTION_BANK")
                                .param("requestId", UUID.randomUUID().toString())
                                .param("namespace", "ets")
                                .with(user(contentAdminPrincipal))
                                .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName(
            "AC15/16/17: Upload JSON Question Bank chạy DryRun, phân tách standalone question và group, ghi nhận VALID/INVALID")
    void uploadQuestionBankJsonDryRunCreatesJobAndRows() throws Exception {
        org.mockito.Mockito.when(mediaStorage.bucket()).thenReturn("actual-import-bucket");
        UUID requestId = UUID.randomUUID();
        UUID opt1 = UUID.randomUUID();
        UUID opt2 = UUID.randomUUID();
        UUID optG1A = UUID.randomUUID();
        UUID optG1B = UUID.randomUUID();
        UUID optG2A = UUID.randomUUID();
        UUID optG2B = UUID.randomUUID();
        UUID optBad = UUID.randomUUID();

        // 1 standalone question, 1 valid group with 2 inline children (TOEIC Part 7), 1 invalid
        // question (missing prompt)
        String json =
                """
                {
                  "schema_version": 1,
                  "items": [
                    {
                      "type": "QUESTION",
                      "external_key": "q-dry-01",
                      "prompt": "She ____ to the cinema yesterday.",
                      "category": "GRAMMAR",
                      "difficulty": "EASY",
                      "options": [
                        {"id": "%s", "position": 1, "body": "go"},
                        {"id": "%s", "position": 2, "body": "went"}
                      ],
                      "answer_key": {"correctOptionId": "%s"},
                      "source_id": "%s"
                    },
                    {
                      "type": "QUESTION_GROUP",
                      "external_key": "grp-dry-01",
                      "group_type": "PASSAGE",
                      "category": "TOEIC",
                      "part_number": 7,
                      "title": "Email Memo",
                      "materials": [
                        {
                          "position": 1,
                          "material_type": "TEXT",
                          "usage_role": "STIMULUS",
                          "body": "Please note that the office will close at 5 PM."
                        }
                      ],
                      "children": [
                        {
                          "external_key": "q-grp-child-01",
                          "prompt": "What time will the office close?",
                          "category": "TOEIC",
                          "part_number": 7,
                          "options": [
                            {"id": "%s", "position": 1, "body": "At 5 PM"},
                            {"id": "%s", "position": 2, "body": "At 6 PM"}
                          ],
                          "answer_key": {"correctOptionId": "%s"}
                        },
                        {
                          "external_key": "q-grp-child-02",
                          "prompt": "Why will the office close early?",
                          "category": "TOEIC",
                          "part_number": 7,
                          "options": [
                            {"id": "%s", "position": 1, "body": "Special event"},
                            {"id": "%s", "position": 2, "body": "Renovation"}
                          ],
                          "answer_key": {"correctOptionId": "%s"}
                        }
                      ]
                    },
                    {
                      "type": "QUESTION",
                      "external_key": "q-dry-bad",
                      "prompt": "",
                      "category": "GRAMMAR",
                      "options": [
                        {"id": "%s", "position": 1, "body": "opt 1"}
                      ],
                      "answer_key": {"correctOptionId": "%s"}
                    }
                  ]
                }
                """
                        .formatted(
                                opt1,
                                opt2,
                                opt2,
                                activeSource.getId(),
                                optG1A,
                                optG1B,
                                optG1A,
                                optG2A,
                                optG2B,
                                optG2A,
                                optBad,
                                optBad);

        MockMultipartFile file =
                new MockMultipartFile(
                        "file",
                        "questions.json",
                        "application/json",
                        json.getBytes(StandardCharsets.UTF_8));

        MvcResult result =
                mockMvc.perform(
                                multipart("/api/v1/admin/import/jobs")
                                        .file(file)
                                        .param("module", "QUESTION_BANK")
                                        .param("requestId", requestId.toString())
                                        .param("namespace", "ets-prep")
                                        .with(user(contentAdminPrincipal))
                                        .with(csrf()))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.status").value("READY"))
                        .andExpect(jsonPath("$.totalRows").value(3))
                        .andExpect(jsonPath("$.failedRows").value(1))
                        .andReturn();

        String resBody = result.getResponse().getContentAsString();
        UUID jobId = UUID.fromString(objectMapper.readTree(resBody).get("id").asText());

        // Verify storage allocation is RESERVED
        StorageAllocation alloc =
                allocationRepository.findByAllocationKey("import:" + jobId).orElseThrow();
        assertThat(alloc.getState()).isEqualTo("RESERVED");
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

        // Verify rows: row 1 VALID, row 2 VALID, row 3 INVALID
        mockMvc.perform(
                        get("/api/v1/admin/import/jobs/" + jobId + "/rows")
                                .with(user(contentAdminPrincipal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.items[0].rowNumber").value(1))
                .andExpect(jsonPath("$.items[0].status").value("VALID"))
                .andExpect(jsonPath("$.items[0].externalKey").value("q-dry-01"))
                .andExpect(jsonPath("$.items[1].rowNumber").value(2))
                .andExpect(jsonPath("$.items[1].status").value("VALID"))
                .andExpect(jsonPath("$.items[1].externalKey").value("grp-dry-01"))
                .andExpect(jsonPath("$.items[2].rowNumber").value(3))
                .andExpect(jsonPath("$.items[2].status").value("INVALID"))
                .andExpect(
                        jsonPath("$.items[2].errors")
                                .value(org.hamcrest.Matchers.containsString("prompt")));

        // Replay with exact same requestId -> 200 OK
        mockMvc.perform(
                        multipart("/api/v1/admin/import/jobs")
                                .file(file)
                                .param("module", "QUESTION_BANK")
                                .param("requestId", requestId.toString())
                                .param("namespace", "ets-prep")
                                .with(user(contentAdminPrincipal))
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(jobId.toString()));

        // Cross-owner 404
        mockMvc.perform(get("/api/v1/admin/import/jobs/" + jobId).with(user(otherAdminPrincipal)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName(
            "AC15/16/17: Confirm JSON import tạo Draft Question & Question Group, rollback atomic toàn bộ group nếu child lỗi")
    void confirmQuestionBankJobProcessesRowsAndRollsBackAtomicGroupOnBadChild() throws Exception {
        UUID optColl = UUID.randomUUID();
        // Pre-create an item to cause collision on child 2 of Row 3
        authoringService.createItemWithDraft(
                UUID.randomUUID(),
                "QUESTION",
                contentAdmin.getId(),
                "ns-qb",
                "collision-child-key",
                "{\"prompt\":\"Existing item\",\"category\":\"GRAMMAR\",\"options\":[{\"id\":\""
                        + optColl
                        + "\",\"position\":1,\"body\":\"opt\"}],\"answer_key\":{\"correctOptionId\":\""
                        + optColl
                        + "\"}}",
                "Collision item",
                List.of());

        UUID requestId = UUID.randomUUID();
        UUID opt1A = UUID.randomUUID();
        UUID opt1B = UUID.randomUUID();
        UUID opt2A = UUID.randomUUID();
        UUID opt2B = UUID.randomUUID();
        UUID opt3A = UUID.randomUUID();
        UUID opt3B = UUID.randomUUID();
        UUID opt4A = UUID.randomUUID();
        UUID opt4B = UUID.randomUUID();
        UUID opt5A = UUID.randomUUID();
        UUID opt5B = UUID.randomUUID();

        // Row 1: Valid standalone Question
        // Row 2: Valid Question Group with 2 inline children
        // Row 3: Question Group with child 1 valid, child 2 colliding on external_key -> fails on
        // confirm execution
        String json =
                """
                {
                  "schema_version": 1,
                  "items": [
                    {
                      "type": "QUESTION",
                      "external_key": "q-cf-01",
                      "prompt": "Choose the correct preposition: He is interested ____ music.",
                      "category": "GRAMMAR",
                      "difficulty": "MEDIUM",
                      "options": [
                        {"id": "%s", "position": 1, "body": "in"},
                        {"id": "%s", "position": 2, "body": "on"}
                      ],
                      "answer_key": {"correctOptionId": "%s"},
                      "source_id": "%s"
                    },
                    {
                      "type": "QUESTION_GROUP",
                      "external_key": "grp-cf-01",
                      "group_type": "PASSAGE",
                      "category": "TOEIC",
                      "part_number": 7,
                      "title": "Notice",
                      "materials": [
                        {
                          "position": 1,
                          "material_type": "TEXT",
                          "usage_role": "STIMULUS",
                          "body": "Meeting room B is under maintenance."
                        }
                      ],
                      "children": [
                        {
                          "external_key": "q-cf-child-01",
                          "prompt": "Which room is being maintained?",
                          "category": "TOEIC",
                          "part_number": 7,
                          "options": [
                            {"id": "%s", "position": 1, "body": "Room A"},
                            {"id": "%s", "position": 2, "body": "Room B"}
                          ],
                          "answer_key": {"correctOptionId": "%s"}
                        },
                        {
                          "external_key": "q-cf-child-02",
                          "prompt": "Why is the room closed?",
                          "category": "TOEIC",
                          "part_number": 7,
                          "options": [
                            {"id": "%s", "position": 1, "body": "For maintenance"},
                            {"id": "%s", "position": 2, "body": "For a party"}
                          ],
                          "answer_key": {"correctOptionId": "%s"}
                        }
                      ]
                    },
                    {
                      "type": "QUESTION_GROUP",
                      "external_key": "grp-cf-atomic-fail",
                      "group_type": "PASSAGE",
                      "category": "TOEIC",
                      "part_number": 7,
                      "title": "Ad",
                      "materials": [
                        {
                          "position": 1,
                          "material_type": "TEXT",
                          "usage_role": "STIMULUS",
                          "body": "Ad text."
                        }
                      ],
                      "children": [
                        {
                          "external_key": "q-cf-child-fail-01",
                          "prompt": "First child prompt",
                          "category": "TOEIC",
                          "part_number": 7,
                          "options": [
                            {"id": "%s", "position": 1, "body": "Opt A"},
                            {"id": "%s", "position": 2, "body": "Opt B"}
                          ],
                          "answer_key": {"correctOptionId": "%s"}
                        },
                        {
                          "external_key": "collision-child-key",
                          "prompt": "Second child prompt colliding",
                          "category": "TOEIC",
                          "part_number": 7,
                          "options": [
                            {"id": "%s", "position": 1, "body": "Opt A"},
                            {"id": "%s", "position": 2, "body": "Opt B"}
                          ],
                          "answer_key": {"correctOptionId": "%s"}
                        }
                      ]
                    }
                  ]
                }
                """
                        .formatted(
                                opt1A,
                                opt1B,
                                opt1A,
                                activeSource.getId(),
                                opt2A,
                                opt2B,
                                opt2B,
                                opt3A,
                                opt3B,
                                opt3A,
                                opt4A,
                                opt4B,
                                opt4A,
                                opt5A,
                                opt5B,
                                opt5A);

        MockMultipartFile file =
                new MockMultipartFile(
                        "file",
                        "qb.json",
                        "application/json",
                        json.getBytes(StandardCharsets.UTF_8));

        MvcResult result =
                mockMvc.perform(
                                multipart("/api/v1/admin/import/jobs")
                                        .file(file)
                                        .param("module", "QUESTION_BANK")
                                        .param("requestId", requestId.toString())
                                        .param("namespace", "ns-qb")
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
                .andExpect(jsonPath("$.totalRows").value(3))
                .andExpect(jsonPath("$.successRows").value(2))
                .andExpect(jsonPath("$.failedRows").value(1));

        // 1. Verify standalone Question item & draft revision
        ContentItem q1Item =
                itemRepository
                        .findByImportNamespaceAndExternalKey("ns-qb", "q-cf-01")
                        .orElseThrow();
        assertThat(q1Item.getContentType()).isEqualTo("QUESTION");
        assertThat(q1Item.getPublishedRevisionId()).isNull(); // Must be draft!

        List<ContentRevision> q1Revs =
                revisionRepository.findByContentItemIdOrderByVersionNoDesc(q1Item.getId());
        assertThat(q1Revs).hasSize(1);
        assertThat(q1Revs.get(0).getStatus()).isEqualTo("DRAFT");
        assertThat(q1Revs.get(0).getSnapshot()).contains("preposition");

        // 2. Verify Question Group & inline children
        ContentItem grpItem =
                itemRepository
                        .findByImportNamespaceAndExternalKey("ns-qb", "grp-cf-01")
                        .orElseThrow();
        assertThat(grpItem.getContentType()).isEqualTo("QUESTION_GROUP");
        assertThat(grpItem.getPublishedRevisionId()).isNull();

        ContentItem child1Item =
                itemRepository
                        .findByImportNamespaceAndExternalKey("ns-qb", "q-cf-child-01")
                        .orElseThrow();
        assertThat(child1Item.getContentType()).isEqualTo("QUESTION");

        ContentItem child2Item =
                itemRepository
                        .findByImportNamespaceAndExternalKey("ns-qb", "q-cf-child-02")
                        .orElseThrow();
        assertThat(child2Item.getContentType()).isEqualTo("QUESTION");

        // 3. Verify Atomic Rollback: neither grp-cf-atomic-fail nor q-cf-child-fail-01 must exist
        // in DB!
        assertThat(
                        itemRepository.findByImportNamespaceAndExternalKey(
                                "ns-qb", "grp-cf-atomic-fail"))
                .isEmpty();
        assertThat(
                        itemRepository.findByImportNamespaceAndExternalKey(
                                "ns-qb", "q-cf-child-fail-01"))
                .isEmpty();

        StorageAllocation alloc =
                allocationRepository.findByAllocationKey("import:" + jobId).orElseThrow();
        assertThat(alloc.getState()).isEqualTo("RESERVED");
        assertThat(alloc.getReleasedAt()).isNull();
    }
}
