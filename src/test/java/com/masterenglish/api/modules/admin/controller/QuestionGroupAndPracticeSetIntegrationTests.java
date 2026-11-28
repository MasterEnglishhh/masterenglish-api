package com.masterenglish.api.modules.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masterenglish.api.TestcontainersConfiguration;
import com.masterenglish.api.common.security.RoleCode;
import com.masterenglish.api.infrastructure.security.UserPrincipal;
import com.masterenglish.api.modules.admin.dto.request.CreatePracticeSetDraftRequest;
import com.masterenglish.api.modules.admin.dto.request.CreateQuestionDraftRequest;
import com.masterenglish.api.modules.admin.dto.request.CreateQuestionGroupDraftRequest;
import com.masterenglish.api.modules.admin.dto.request.CreateRevisionFromPublishedRequest;
import com.masterenglish.api.modules.admin.dto.request.ReviewDecisionRequest;
import com.masterenglish.api.modules.content.entity.ContentSource;
import com.masterenglish.api.modules.content.entity.MediaAsset;
import com.masterenglish.api.modules.content.repository.ContentItemRepository;
import com.masterenglish.api.modules.content.repository.ContentRevisionRepository;
import com.masterenglish.api.modules.content.repository.ContentSourceLinkRepository;
import com.masterenglish.api.modules.content.repository.ContentSourceRepository;
import com.masterenglish.api.modules.content.repository.MediaAssetRepository;
import com.masterenglish.api.modules.content.repository.RevisionDependencyRepository;
import com.masterenglish.api.modules.content.repository.RevisionMediaRefRepository;
import com.masterenglish.api.modules.identity.entity.Role;
import com.masterenglish.api.modules.identity.entity.User;
import com.masterenglish.api.modules.identity.repository.RoleRepository;
import com.masterenglish.api.modules.identity.repository.UserRepository;
import com.masterenglish.api.modules.practice.entity.PracticeSet;
import com.masterenglish.api.modules.practice.entity.QuestionGroup;
import com.masterenglish.api.modules.practice.repository.GroupMaterialRepository;
import com.masterenglish.api.modules.practice.repository.PracticeSetRepository;
import com.masterenglish.api.modules.practice.repository.QuestionGroupRepository;
import com.masterenglish.api.modules.practice.repository.QuestionRepository;
import com.masterenglish.api.modules.practice.repository.SetItemRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
@EnabledIf("isDockerAvailable")
class QuestionGroupAndPracticeSetIntegrationTests {

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
    @Autowired private ContentItemRepository itemRepository;
    @Autowired private ContentRevisionRepository revisionRepository;
    @Autowired private ContentSourceRepository sourceRepository;
    @Autowired private ContentSourceLinkRepository sourceLinkRepository;
    @Autowired private MediaAssetRepository mediaAssetRepository;
    @Autowired private RevisionMediaRefRepository mediaRefRepository;
    @Autowired private RevisionDependencyRepository dependencyRepository;
    @Autowired private QuestionRepository questionRepository;
    @Autowired private QuestionGroupRepository questionGroupRepository;
    @Autowired private GroupMaterialRepository groupMaterialRepository;
    @Autowired private PracticeSetRepository practiceSetRepository;
    @Autowired private SetItemRepository setItemRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

    private MockMvc mockMvc;
    private User contentAdminUser;
    private User learnerUser;
    private UserPrincipal contentAdminPrincipal;
    private UserPrincipal learnerPrincipal;
    private ContentSource activeSource;

    private UserPrincipal adminPrincipal() {
        return contentAdminPrincipal;
    }

    private UserPrincipal learnerPrincipal() {
        return learnerPrincipal;
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        transactionTemplate.execute(
                status -> {
                    Role adminRole =
                            roleRepository.findByCode(RoleCode.CONTENT_ADMIN).orElseThrow();
                    Role learnerRole = roleRepository.findByCode(RoleCode.LEARNER).orElseThrow();

                    contentAdminUser =
                            userRepository.save(
                                    new User(
                                            "group-admin-" + UUID.randomUUID() + "@example.com",
                                            "hash",
                                            "Admin",
                                            "User"));
                    contentAdminUser.addRole(adminRole);
                    contentAdminUser.markEmailVerified(Instant.now());
                    contentAdminUser = userRepository.saveAndFlush(contentAdminUser);
                    contentAdminPrincipal = UserPrincipal.create(contentAdminUser);

                    learnerUser =
                            userRepository.save(
                                    new User(
                                            "learner-" + UUID.randomUUID() + "@example.com",
                                            "hash",
                                            "Learner",
                                            "User"));
                    learnerUser.addRole(learnerRole);
                    learnerUser.markEmailVerified(Instant.now());
                    learnerUser = userRepository.saveAndFlush(learnerUser);
                    learnerPrincipal = UserPrincipal.create(learnerUser);

                    activeSource =
                            sourceRepository.save(
                                    new ContentSource(
                                            UUID.randomUUID(),
                                            "Official TOEIC Practice Tests",
                                            "EXTERNAL",
                                            "https://example.com/toeic-tests",
                                            "ETS",
                                            "COPYRIGHT_LICENSED",
                                            null,
                                            "ETS Toeic Prep",
                                            "Authorized educational license",
                                            "ACTIVE",
                                            contentAdminUser.getId()));

                    return null;
                });
    }

    private MediaAsset createReadyMedia(String type, String mediaType) {
        UUID mediaId = UUID.randomUUID();
        MediaAsset asset =
                new MediaAsset(
                        mediaId,
                        type,
                        "CONTENT",
                        "stimulus-file." + (type.equals("AUDIO") ? "mp3" : "jpg"),
                        mediaType,
                        activeSource.getId(),
                        contentAdminUser.getId(),
                        "PENDING");
        asset.markReady(
                "masterenglish-content",
                "content/" + mediaId,
                4096L,
                "556ec8450ed7e439aa4beabee80176cbf675348a4efb8a45640bc77af943bf3c",
                type.equals("IMAGE") ? 800 : null,
                type.equals("IMAGE") ? 600 : null,
                type.equals("AUDIO") ? 5000 : null);
        return mediaAssetRepository.saveAndFlush(asset);
    }

    private List<Map<String, Object>> makeOptions(int count) {
        List<Map<String, Object>> options = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            options.add(
                    Map.of(
                            "id",
                            UUID.randomUUID().toString(),
                            "position",
                            i,
                            "body",
                            "Option " + (char) ('A' + i - 1)));
        }
        return options;
    }

    private UUID latestPublishedRevision(UUID itemId) {
        return revisionRepository.findByContentItemIdOrderByVersionNoDesc(itemId).stream()
                .filter(revision -> "PUBLISHED".equals(revision.getStatus()))
                .findFirst()
                .orElseThrow()
                .getId();
    }

    private UUID createAndPublishQuestion(String category, Integer partNumber, String prompt)
            throws Exception {
        int optCount = (partNumber != null && partNumber == 2) ? 3 : 4;
        List<Map<String, Object>> options = makeOptions(optCount);
        String correctId = (String) options.get(0).get("id");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("category", category);
        data.put("part_number", partNumber);
        data.put("question_type_code", "SINGLE_CHOICE");
        data.put("practice_mode", "STANDARD");
        data.put("difficulty", "MEDIUM");
        data.put("prompt", prompt);
        data.put("options", options);
        data.put("answer_key", Map.of("correctOptionId", correctId));
        data.put("explanation", "Standard explanation for " + prompt);
        data.put("materials", List.of());
        data.put("topic_ids", List.of());
        data.put("word_sense_ids", List.of());

        CreateQuestionDraftRequest createReq =
                new CreateQuestionDraftRequest(
                        data, List.of(activeSource.getId()), "Tạo câu hỏi: " + prompt);
        MvcResult res =
                mockMvc.perform(
                                post("/api/v1/admin/content/questions")
                                        .with(user(adminPrincipal()))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(createReq)))
                        .andExpect(status().isCreated())
                        .andReturn();

        JsonNode json = objectMapper.readTree(res.getResponse().getContentAsString());
        UUID itemId = UUID.fromString(json.path("id").asText());
        UUID revId = UUID.fromString(json.path("latestRevisionId").asText());

        // Submit -> Approve -> Publish
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + revId + "/submit")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());

        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + revId + "/approve")
                                .with(user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new ReviewDecisionRequest("Duyệt"))))
                .andExpect(status().isOk());

        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + revId + "/publish")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());

        return itemId;
    }

    @Test
    @DisplayName(
            "TOEIC Part 3 Question Group: tạo bản nháp, submit, approve, publish và chiếu projection nguyên tử")
    void toeicPart3QuestionGroup_publish_successAndProjections() throws Exception {
        UUID q1 = createAndPublishQuestion("TOEIC", 3, "Where does the conversation take place?");
        UUID q2 = createAndPublishQuestion("TOEIC", 3, "What problem does the woman mention?");
        UUID q3 = createAndPublishQuestion("TOEIC", 3, "What will the man do next?");

        MediaAsset audioMedia = createReadyMedia("AUDIO", "audio/mpeg");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("category", "TOEIC");
        data.put("part_number", 3);
        data.put("group_type", "CONVERSATION");
        data.put("title", "Airport Terminal Flight Delay Conversation");
        data.put(
                "materials",
                List.of(
                        Map.of(
                                "id",
                                UUID.randomUUID().toString(),
                                "position",
                                1,
                                "material_type",
                                "AUDIO",
                                "usage_role",
                                "STIMULUS",
                                "media_id",
                                audioMedia.getId().toString())));
        data.put(
                "children",
                List.of(
                        Map.of("question_id", q1.toString(), "position", 1),
                        Map.of("question_id", q2.toString(), "position", 2),
                        Map.of("question_id", q3.toString(), "position", 3)));

        CreateQuestionGroupDraftRequest createReq =
                new CreateQuestionGroupDraftRequest(
                        data, List.of(activeSource.getId()), "Part 3 Group conversation");

        MvcResult createResult =
                mockMvc.perform(
                                post("/api/v1/admin/content/question-groups")
                                        .with(user(adminPrincipal()))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(createReq)))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.type").value("QUESTION_GROUP"))
                        .andExpect(jsonPath("$.status").value("DRAFT"))
                        .andExpect(
                                jsonPath("$.title")
                                        .value("Airport Terminal Flight Delay Conversation"))
                        .andReturn();

        JsonNode json = objectMapper.readTree(createResult.getResponse().getContentAsString());
        UUID groupId = UUID.fromString(json.path("id").asText());
        UUID revId = UUID.fromString(json.path("latestRevisionId").asText());

        // Submit -> Approve -> Publish
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + revId + "/submit")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_REVIEW"));

        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + revId + "/approve")
                                .with(user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new ReviewDecisionRequest("Duyệt nhóm Part 3"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + revId + "/publish")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));

        // Verify Database Projections
        QuestionGroup group = questionGroupRepository.findById(groupId).orElseThrow();
        assertThat(group.getCategory()).isEqualTo("TOEIC");
        assertThat(group.getPartNumber()).isEqualTo(3);
        assertThat(group.getGroupType()).isEqualTo("CONVERSATION");
        assertThat(group.getTitle()).isEqualTo("Airport Terminal Flight Delay Conversation");
        assertThat(group.getPublishedRevisionId()).isEqualTo(revId);

        var snapshot =
                objectMapper.readTree(
                        revisionRepository.findById(revId).orElseThrow().getSnapshot());
        var children = snapshot.path("data").path("children");
        var childIds = List.of(q1, q2, q3);
        for (int i = 0; i < childIds.size(); i++) {
            UUID childId = childIds.get(i);
            UUID publishedChildRevision = latestPublishedRevision(childId);
            assertThat(children.path(i).path("revision_id").asText())
                    .isEqualTo(publishedChildRevision.toString());
            assertThat(dependencyRepository.findByIdParentRevisionId(revId))
                    .anyMatch(
                            d ->
                                    d.getId().getRelationKey().equals("group_child:" + childId)
                                            && d.getChildRevisionId()
                                                    .equals(publishedChildRevision));
        }

        // Verify Group Materials
        var materials = groupMaterialRepository.findByGroupIdOrderByPositionAsc(groupId);
        assertThat(materials).hasSize(1);
        assertThat(materials.get(0).getMaterialType()).isEqualTo("AUDIO");
        assertThat(materials.get(0).getUsageRole()).isEqualTo("STIMULUS");
        assertThat(materials.get(0).getMediaId()).isEqualTo(audioMedia.getId());

        // Verify Child Questions assigned to Group
        var child1 = questionRepository.findById(q1).orElseThrow();
        var child2 = questionRepository.findById(q2).orElseThrow();
        var child3 = questionRepository.findById(q3).orElseThrow();
        assertThat(child1.getGroupId()).isEqualTo(groupId);
        assertThat(child1.getGroupPosition()).isEqualTo(1);
        assertThat(child1.isInCurrentGroup()).isTrue();

        assertThat(child2.getGroupId()).isEqualTo(groupId);
        assertThat(child2.getGroupPosition()).isEqualTo(2);

        assertThat(child3.getGroupId()).isEqualTo(groupId);
        assertThat(child3.getGroupPosition()).isEqualTo(3);

        // Verify Revision Dependencies pinned
        var deps = dependencyRepository.findByIdParentRevisionId(revId);
        assertThat(deps).hasSize(3);
        assertThat(deps).allMatch(d -> d.getId().getRelationKey().startsWith("group_child:"));

        // Verify Media Refs
        var mediaRefs = mediaRefRepository.findByRevisionId(revId);
        assertThat(mediaRefs).hasSize(1);
        assertThat(mediaRefs.get(0).getMediaId()).isEqualTo(audioMedia.getId());

        UUID nextChildRevision = approveQuestionRevision(q1);
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + nextChildRevision + "/publish")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());
        assertThat(latestPublishedRevision(q1)).isEqualTo(nextChildRevision);
        assertThat(
                        objectMapper.readTree(
                                revisionRepository.findById(revId).orElseThrow().getSnapshot()))
                .isEqualTo(snapshot);
    }

    private UUID approveQuestionRevision(UUID questionId) throws Exception {
        UUID publishedRevisionId =
                itemRepository.findById(questionId).orElseThrow().getPublishedRevisionId();
        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/admin/content/items/" + questionId + "/revisions")
                                        .with(user(adminPrincipal()))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        new CreateRevisionFromPublishedRequest(
                                                                publishedRevisionId,
                                                                null,
                                                                "Approved child"))))
                        .andExpect(status().isCreated())
                        .andReturn();
        UUID revisionId =
                UUID.fromString(
                        objectMapper
                                .readTree(result.getResponse().getContentAsString())
                                .path("revisionId")
                                .asText());
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + revisionId + "/submit")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + revisionId + "/approve")
                                .with(user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new ReviewDecisionRequest("Approve child"))))
                .andExpect(status().isOk());
        return revisionId;
    }

    private UUID createAndPublishGroupWithChild(UUID questionId, UUID childRevisionId)
            throws Exception {
        MediaAsset audio = createReadyMedia("AUDIO", "audio/mpeg");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("category", "TOEIC");
        data.put("part_number", 3);
        data.put("group_type", "CONVERSATION");
        data.put("title", "Approved child group");
        data.put(
                "materials",
                List.of(
                        Map.of(
                                "position",
                                1,
                                "material_type",
                                "AUDIO",
                                "usage_role",
                                "STIMULUS",
                                "media_id",
                                audio.getId().toString())));
        List<Map<String, Object>> children = new ArrayList<>();
        children.add(
                Map.of(
                        "question_id",
                        questionId.toString(),
                        "revision_id",
                        childRevisionId.toString(),
                        "position",
                        1));
        for (int i = 2; i <= 3; i++) {
            UUID childId = createAndPublishQuestion("TOEIC", 3, "Question " + i + " in group");
            children.add(Map.of("question_id", childId.toString(), "position", i));
        }
        data.put("children", children);
        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/admin/content/question-groups")
                                        .with(user(adminPrincipal()))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        new CreateQuestionGroupDraftRequest(
                                                                data,
                                                                List.of(activeSource.getId()),
                                                                "Approved child group"))))
                        .andExpect(status().isCreated())
                        .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID groupId = UUID.fromString(json.path("id").asText());
        UUID groupRevisionId = UUID.fromString(json.path("latestRevisionId").asText());
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + groupRevisionId + "/submit")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + groupRevisionId + "/approve")
                                .with(user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new ReviewDecisionRequest("Approve group"))))
                .andExpect(status().isOk());
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + groupRevisionId + "/publish")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());
        return groupId;
    }

    private UUID latestRevisionId(UUID itemId) {
        return revisionRepository.findByContentItemIdOrderByVersionNoDesc(itemId).stream()
                .findFirst()
                .orElseThrow()
                .getId();
    }

    private void submitAndApprove(UUID revisionId) throws Exception {
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + revisionId + "/submit")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + revisionId + "/approve")
                                .with(user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new ReviewDecisionRequest("Approve group"))))
                .andExpect(status().isOk());
    }

    private UUID createGroupDraftWithApprovedChildren(
            UUID firstQuestionId,
            UUID firstRevisionId,
            UUID secondQuestionId,
            UUID secondRevisionId)
            throws Exception {
        MediaAsset audio = createReadyMedia("AUDIO", "audio/mpeg");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("category", "TOEIC");
        data.put("part_number", 3);
        data.put("group_type", "CONVERSATION");
        data.put("title", "Rollback group");
        data.put(
                "materials",
                List.of(
                        Map.of(
                                "position",
                                1,
                                "material_type",
                                "AUDIO",
                                "usage_role",
                                "STIMULUS",
                                "media_id",
                                audio.getId().toString())));
        List<Map<String, Object>> children = new ArrayList<>();
        children.add(
                Map.of(
                        "question_id",
                        firstQuestionId.toString(),
                        "revision_id",
                        firstRevisionId.toString(),
                        "position",
                        1));
        children.add(
                Map.of(
                        "question_id",
                        secondQuestionId.toString(),
                        "revision_id",
                        secondRevisionId.toString(),
                        "position",
                        2));
        UUID thirdQuestionId = createAndPublishQuestion("TOEIC", 3, "Third rollback child");
        children.add(Map.of("question_id", thirdQuestionId.toString(), "position", 3));
        data.put("children", children);
        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/admin/content/question-groups")
                                        .with(user(adminPrincipal()))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        new CreateQuestionGroupDraftRequest(
                                                                data,
                                                                List.of(activeSource.getId()),
                                                                "Rollback group"))))
                        .andExpect(status().isCreated())
                        .andReturn();
        return UUID.fromString(
                objectMapper
                        .readTree(result.getResponse().getContentAsString())
                        .path("id")
                        .asText());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void questionGroupPublishRejectsExplicitPinToUnpublishedChild() throws Exception {
        UUID first = createAndPublishQuestion("TOEIC", 3, "Child withdrawn before group publish");
        UUID second = createAndPublishQuestion("TOEIC", 3, "Visible child");
        UUID group =
                createGroupDraftWithApprovedChildren(
                        first,
                        latestPublishedRevision(first),
                        second,
                        latestPublishedRevision(second));
        UUID revision = latestRevisionId(group);
        submitAndApprove(revision);
        mockMvc.perform(
                        post("/api/v1/admin/content/items/" + first + "/unpublish")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + revision + "/publish")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isBadRequest());
        assertThat(revisionRepository.findById(revision).orElseThrow().getStatus())
                .isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("Question Group rollback khi child APPROVED cuối cùng sai schema")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void questionGroupPublish_laterInvalidApprovedChildRollsBackEarlierChildAndGroup()
            throws Exception {
        UUID firstQuestionId = createAndPublishQuestion("TOEIC", 3, "First child");
        UUID lastQuestionId = createAndPublishQuestion("TOEIC", 3, "Last child");
        UUID firstApprovedRevision = approveQuestionRevision(firstQuestionId);
        UUID lastApprovedRevision = approveQuestionRevision(lastQuestionId);
        var lastRevision = revisionRepository.findById(lastApprovedRevision).orElseThrow();
        var invalidSnapshot = objectMapper.readTree(lastRevision.getSnapshot());
        ((com.fasterxml.jackson.databind.node.ObjectNode) invalidSnapshot.path("data"))
                .put("unsupported_field", true);
        jdbcTemplate.update(
                "UPDATE content_revisions SET snapshot = ?::jsonb WHERE id = ?",
                invalidSnapshot.toString(),
                lastApprovedRevision);

        UUID groupId =
                createGroupDraftWithApprovedChildren(
                        firstQuestionId,
                        firstApprovedRevision,
                        lastQuestionId,
                        lastApprovedRevision);
        UUID groupRevisionId = latestRevisionId(groupId);
        submitAndApprove(groupRevisionId);

        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + groupRevisionId + "/publish")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isBadRequest());

        assertThat(revisionRepository.findById(firstApprovedRevision).orElseThrow().getStatus())
                .isEqualTo("APPROVED");
        assertThat(itemRepository.findById(firstQuestionId).orElseThrow().getPublishedRevisionId())
                .isNotEqualTo(firstApprovedRevision);
        assertThat(revisionRepository.findById(groupRevisionId).orElseThrow().getStatus())
                .isEqualTo("APPROVED");
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM question_groups WHERE id = ?",
                                Integer.class,
                                groupId))
                .isZero();
    }

    @Test
    @DisplayName(
            "Question Group tự publish câu APPROVED bằng actor hiện tại và chụp đủ audit/source")
    void questionGroupPublish_approvedChildUsesPublisherAndCanonicalEffects() throws Exception {
        UUID questionId = createAndPublishQuestion("TOEIC", 3, "Which gate is used?");
        UUID approvedRevisionId = approveQuestionRevision(questionId);
        UUID groupId = createAndPublishGroupWithChild(questionId, approvedRevisionId);

        var publishedChild = revisionRepository.findById(approvedRevisionId).orElseThrow();
        assertThat(publishedChild.getStatus()).isEqualTo("PUBLISHED");
        assertThat(publishedChild.getReviewedBy()).isEqualTo(contentAdminUser.getId());
        assertThat(itemRepository.findById(questionId).orElseThrow().getPublishedRevisionId())
                .isEqualTo(approvedRevisionId);
        assertThat(questionRepository.findById(questionId).orElseThrow().getPublishedRevisionId())
                .isEqualTo(approvedRevisionId);
        assertThat(questionRepository.findById(questionId).orElseThrow().getGroupId())
                .isEqualTo(groupId);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM audit_logs WHERE entity_id = ? AND action = 'CONTENT_PUBLISHED' AND actor_user_id = ? AND metadata->>'revisionId' = ?",
                                Integer.class,
                                questionId,
                                contentAdminUser.getId(),
                                approvedRevisionId.toString()))
                .isEqualTo(1);
        assertThat(sourceLinkRepository.findByIdRevisionId(approvedRevisionId))
                .hasSize(1)
                .allMatch(link -> link.getSourceSnapshot() != null);
    }

    @Test
    @DisplayName("TOEIC Part 6 Question Group: Text completion với blank markers và 4 câu con")
    void toeicPart6QuestionGroup_publish_successAndProjections() throws Exception {
        UUID q1 = createAndPublishQuestion("TOEIC", 6, "Choose word for blank 1");
        UUID q2 = createAndPublishQuestion("TOEIC", 6, "Choose word for blank 2");
        UUID q3 = createAndPublishQuestion("TOEIC", 6, "Choose sentence for blank 3");
        UUID q4 = createAndPublishQuestion("TOEIC", 6, "Choose word for blank 4");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("category", "TOEIC");
        data.put("part_number", 6);
        data.put("group_type", "TEXT_COMPLETION");
        data.put("title", "Annual General Meeting Announcement");
        data.put(
                "materials",
                List.of(
                        Map.of(
                                "position",
                                1,
                                "material_type",
                                "TEXT",
                                "usage_role",
                                "STIMULUS",
                                "body",
                                "To all shareholders: We are pleased to announce our annual meeting {{blank:b1}}. "
                                        + "The board will {{blank:b2}} present the fiscal year report. {{blank:b3}} "
                                        + "Please register before Friday {{blank:b4}} to attend.")));
        data.put(
                "children",
                List.of(
                        Map.of("question_id", q1.toString(), "position", 1, "blank_key", "b1"),
                        Map.of("question_id", q2.toString(), "position", 2, "blank_key", "b2"),
                        Map.of("question_id", q3.toString(), "position", 3, "blank_key", "b3"),
                        Map.of("question_id", q4.toString(), "position", 4, "blank_key", "b4")));

        CreateQuestionGroupDraftRequest createReq =
                new CreateQuestionGroupDraftRequest(
                        data, List.of(activeSource.getId()), "Part 6 Text completion");

        MvcResult createResult =
                mockMvc.perform(
                                post("/api/v1/admin/content/question-groups")
                                        .with(user(adminPrincipal()))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(createReq)))
                        .andExpect(status().isCreated())
                        .andReturn();

        JsonNode json = objectMapper.readTree(createResult.getResponse().getContentAsString());
        UUID groupId = UUID.fromString(json.path("id").asText());
        UUID revId = UUID.fromString(json.path("latestRevisionId").asText());

        // Submit -> Approve -> Publish
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + revId + "/submit")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());

        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + revId + "/approve")
                                .with(user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new ReviewDecisionRequest("Duyệt Part 6"))))
                .andExpect(status().isOk());

        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + revId + "/publish")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());

        // Verify Projections
        QuestionGroup group = questionGroupRepository.findById(groupId).orElseThrow();
        assertThat(group.getGroupType()).isEqualTo("TEXT_COMPLETION");
        assertThat(group.getPartNumber()).isEqualTo(6);

        var child1 = questionRepository.findById(q1).orElseThrow();
        var child2 = questionRepository.findById(q2).orElseThrow();
        assertThat(child1.getBlankKey()).isEqualTo("b1");
        assertThat(child1.getGroupId()).isEqualTo(groupId);
        assertThat(child2.getBlankKey()).isEqualTo("b2");
        assertThat(child2.getGroupId()).isEqualTo(groupId);
    }

    @Test
    @DisplayName("TOEIC Part 7 Question Group: Passage với 2 document_key và 3 câu hỏi con")
    void toeicPart7QuestionGroup_publish_successAndProjections() throws Exception {
        UUID q1 = createAndPublishQuestion("TOEIC", 7, "What is the purpose of the letter?");
        UUID q2 = createAndPublishQuestion("TOEIC", 7, "What discount is mentioned in the flyer?");
        UUID q3 = createAndPublishQuestion("TOEIC", 7, "What will Ms. Lee most likely do?");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("category", "TOEIC");
        data.put("part_number", 7);
        data.put("group_type", "PASSAGE");
        data.put("title", "Double Passage: Business Letter and Promotional Flyer");
        data.put(
                "materials",
                List.of(
                        Map.of(
                                "position", 1,
                                "document_key", "doc1_letter",
                                "material_type", "TEXT",
                                "usage_role", "STIMULUS",
                                "body",
                                        "Dear Valued Customer, We are excited to offer new services."),
                        Map.of(
                                "position", 2,
                                "document_key", "doc2_flyer",
                                "material_type", "TEXT",
                                "usage_role", "STIMULUS",
                                "body",
                                        "Special Discount: 20% off all consultations booked this month.")));
        data.put(
                "children",
                List.of(
                        Map.of("question_id", q1.toString(), "position", 1),
                        Map.of("question_id", q2.toString(), "position", 2),
                        Map.of("question_id", q3.toString(), "position", 3)));

        CreateQuestionGroupDraftRequest createReq =
                new CreateQuestionGroupDraftRequest(
                        data, List.of(activeSource.getId()), "Part 7 Double Passage");

        MvcResult createResult =
                mockMvc.perform(
                                post("/api/v1/admin/content/question-groups")
                                        .with(user(adminPrincipal()))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(createReq)))
                        .andExpect(status().isCreated())
                        .andReturn();

        JsonNode json = objectMapper.readTree(createResult.getResponse().getContentAsString());
        UUID groupId = UUID.fromString(json.path("id").asText());
        UUID revId = UUID.fromString(json.path("latestRevisionId").asText());

        // Submit -> Approve -> Publish
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + revId + "/submit")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());

        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + revId + "/approve")
                                .with(user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new ReviewDecisionRequest("Duyệt Part 7"))))
                .andExpect(status().isOk());

        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + revId + "/publish")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());

        var mats = groupMaterialRepository.findByGroupIdOrderByPositionAsc(groupId);
        assertThat(mats).hasSize(2);
        assertThat(mats.get(0).getDocumentKey()).isEqualTo("doc1_letter");
        assertThat(mats.get(1).getDocumentKey()).isEqualTo("doc2_flyer");
    }

    @Test
    @DisplayName(
            "Validation rejections cho Question Groups: số câu con, markers, document keys, media type")
    void questionGroupValidationRejections() throws Exception {
        UUID q1 = UUID.randomUUID();
        UUID q2 = UUID.randomUUID();

        // 1. Part 3 with 2 children instead of exactly 3
        Map<String, Object> invalidPart3 = new LinkedHashMap<>();
        invalidPart3.put("category", "TOEIC");
        invalidPart3.put("part_number", 3);
        invalidPart3.put("group_type", "CONVERSATION");
        invalidPart3.put("title", "Invalid Part 3");
        invalidPart3.put(
                "materials",
                List.of(
                        Map.of(
                                "position",
                                1,
                                "material_type",
                                "AUDIO",
                                "usage_role",
                                "STIMULUS",
                                "media_id",
                                UUID.randomUUID().toString())));
        invalidPart3.put(
                "children",
                List.of(
                        Map.of("question_id", q1.toString(), "position", 1),
                        Map.of("question_id", q2.toString(), "position", 2)));

        assertGroupValidationFails(invalidPart3, "TOEIC Part 3 yêu cầu đúng 3 câu hỏi.");

        // 2. Part 6 with blank markers mismatch
        Map<String, Object> invalidPart6 = new LinkedHashMap<>();
        invalidPart6.put("category", "TOEIC");
        invalidPart6.put("part_number", 6);
        invalidPart6.put("group_type", "TEXT_COMPLETION");
        invalidPart6.put("title", "Invalid Part 6 Markers");
        invalidPart6.put(
                "materials",
                List.of(
                        Map.of(
                                "position", 1,
                                "material_type", "TEXT",
                                "usage_role", "STIMULUS",
                                "body", "Text with only one marker {{blank:b1}}")));
        invalidPart6.put(
                "children",
                List.of(
                        Map.of(
                                "question_id",
                                UUID.randomUUID().toString(),
                                "position",
                                1,
                                "blank_key",
                                "b1"),
                        Map.of(
                                "question_id",
                                UUID.randomUUID().toString(),
                                "position",
                                2,
                                "blank_key",
                                "b2"),
                        Map.of(
                                "question_id",
                                UUID.randomUUID().toString(),
                                "position",
                                3,
                                "blank_key",
                                "b3"),
                        Map.of(
                                "question_id",
                                UUID.randomUUID().toString(),
                                "position",
                                4,
                                "blank_key",
                                "b4")));

        assertGroupValidationFails(
                invalidPart6,
                "Tập vị trí trống {{blank:<key>}} phải khớp chính xác với blank_key của các câu hỏi con.");

        // 3. Part 7 without document_key
        Map<String, Object> invalidPart7 = new LinkedHashMap<>();
        invalidPart7.put("category", "TOEIC");
        invalidPart7.put("part_number", 7);
        invalidPart7.put("group_type", "PASSAGE");
        invalidPart7.put("title", "Invalid Part 7");
        invalidPart7.put(
                "materials",
                List.of(
                        Map.of(
                                "position", 1,
                                "material_type", "TEXT",
                                "usage_role", "STIMULUS",
                                "body", "Passage text without document key")));
        invalidPart7.put(
                "children",
                List.of(
                        Map.of("question_id", UUID.randomUUID().toString(), "position", 1),
                        Map.of("question_id", UUID.randomUUID().toString(), "position", 2)));

        assertGroupValidationFails(
                invalidPart7, "TOEIC Part 7 yêu cầu từ 1 đến 3 document_key phân biệt.");

        // 4. Non-TOEIC with part_number (fails at draft creation)
        Map<String, Object> nonToeicWithPart = new LinkedHashMap<>();
        nonToeicWithPart.put("category", "VOCABULARY");
        nonToeicWithPart.put("part_number", 1);
        nonToeicWithPart.put("group_type", "GENERAL");
        nonToeicWithPart.put("title", "Invalid Non-TOEIC");
        nonToeicWithPart.put(
                "materials",
                List.of(
                        Map.of(
                                "position", 1,
                                "material_type", "TEXT",
                                "body", "Reading passage")));
        nonToeicWithPart.put(
                "children",
                List.of(Map.of("question_id", UUID.randomUUID().toString(), "position", 1)));

        CreateQuestionGroupDraftRequest draftReq =
                new CreateQuestionGroupDraftRequest(
                        nonToeicWithPart, List.of(activeSource.getId()), "Invalid Non-TOEIC");
        mockMvc.perform(
                        post("/api/v1/admin/content/question-groups")
                                .with(user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(draftReq)))
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.detail").value("part_number phải là null đối với non-TOEIC."));
    }

    private void assertGroupValidationFails(Map<String, Object> data, String expectedMessage)
            throws Exception {
        CreateQuestionGroupDraftRequest req =
                new CreateQuestionGroupDraftRequest(
                        data, List.of(activeSource.getId()), "Validation test");
        MvcResult res =
                mockMvc.perform(
                                post("/api/v1/admin/content/question-groups")
                                        .with(user(adminPrincipal()))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(req)))
                        .andExpect(status().isCreated())
                        .andReturn();

        JsonNode json = objectMapper.readTree(res.getResponse().getContentAsString());
        UUID revId = UUID.fromString(json.path("latestRevisionId").asText());

        // Validate endpoint directly
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + revId + "/validate")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors[0].message").value(expectedMessage));

        // Submit -> Approve -> Publish fails with 400
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + revId + "/submit")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());

        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + revId + "/approve")
                                .with(user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new ReviewDecisionRequest("Duyệt"))))
                .andExpect(status().isOk());

        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + revId + "/publish")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(expectedMessage));
    }

    @Test
    @DisplayName(
            "Practice Set FIXED mode: kết hợp câu độc lập và nhóm câu XOR, submit, approve, publish thành công")
    void practiceSetFixed_publish_successAndProjections() throws Exception {
        UUID standaloneQ = createAndPublishQuestion("TOEIC", 5, "Choose the best preposition.");
        UUID groupQ1 = createAndPublishQuestion("TOEIC", 3, "Where is the man?");
        UUID groupQ2 = createAndPublishQuestion("TOEIC", 3, "Why is the woman delayed?");
        UUID groupQ3 = createAndPublishQuestion("TOEIC", 3, "What will happen at 3 PM?");
        UUID groupRevision1 = latestPublishedRevision(groupQ1);
        UUID groupRevision2 = latestPublishedRevision(groupQ2);
        UUID groupRevision3 = latestPublishedRevision(groupQ3);
        UUID standaloneRevision = latestPublishedRevision(standaloneQ);

        MediaAsset audio = createReadyMedia("AUDIO", "audio/mpeg");

        // Create and publish Group
        Map<String, Object> groupData = new LinkedHashMap<>();
        groupData.put("category", "TOEIC");
        groupData.put("part_number", 3);
        groupData.put("group_type", "CONVERSATION");
        groupData.put("title", "Meeting Delay Conversation");
        groupData.put(
                "materials",
                List.of(
                        Map.of(
                                "position",
                                1,
                                "material_type",
                                "AUDIO",
                                "usage_role",
                                "STIMULUS",
                                "media_id",
                                audio.getId().toString())));
        groupData.put(
                "children",
                List.of(
                        Map.of(
                                "question_id", groupQ1.toString(),
                                "revision_id", groupRevision1.toString(),
                                "position", 1),
                        Map.of(
                                "question_id", groupQ2.toString(),
                                "revision_id", groupRevision2.toString(),
                                "position", 2),
                        Map.of(
                                "question_id", groupQ3.toString(),
                                "revision_id", groupRevision3.toString(),
                                "position", 3)));

        CreateQuestionGroupDraftRequest groupReq =
                new CreateQuestionGroupDraftRequest(
                        groupData, List.of(activeSource.getId()), "Group for Set test");
        MvcResult groupRes =
                mockMvc.perform(
                                post("/api/v1/admin/content/question-groups")
                                        .with(user(adminPrincipal()))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(groupReq)))
                        .andExpect(status().isCreated())
                        .andReturn();

        UUID groupId =
                UUID.fromString(
                        objectMapper
                                .readTree(groupRes.getResponse().getContentAsString())
                                .path("id")
                                .asText());
        UUID groupRevId =
                UUID.fromString(
                        objectMapper
                                .readTree(groupRes.getResponse().getContentAsString())
                                .path("latestRevisionId")
                                .asText());

        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + groupRevId + "/submit")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + groupRevId + "/approve")
                                .with(user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new ReviewDecisionRequest("Duyệt"))))
                .andExpect(status().isOk());
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + groupRevId + "/publish")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());

        // Now create Practice Set in FIXED mode
        Map<String, Object> setData = new LinkedHashMap<>();
        setData.put("category", "TOEIC");
        setData.put("title", "Mixed TOEIC Listening and Reading Mini Set");
        setData.put("selection_mode", "FIXED");
        setData.put("time_limit_seconds", 1200);
        setData.put(
                "entries",
                List.of(
                        Map.of(
                                "position",
                                1,
                                "question_id",
                                standaloneQ.toString(),
                                "pinned_revision_id",
                                standaloneRevision.toString()),
                        Map.of(
                                "position",
                                2,
                                "group_id",
                                groupId.toString(),
                                "pinned_revision_id",
                                groupRevId.toString())));

        CreatePracticeSetDraftRequest setReq =
                new CreatePracticeSetDraftRequest(
                        setData, List.of(activeSource.getId()), "Fixed practice set");

        MvcResult setRes =
                mockMvc.perform(
                                post("/api/v1/admin/content/practice-sets")
                                        .with(user(adminPrincipal()))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(setReq)))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.type").value("PRACTICE_SET"))
                        .andExpect(jsonPath("$.status").value("DRAFT"))
                        .andReturn();

        JsonNode setJson = objectMapper.readTree(setRes.getResponse().getContentAsString());
        UUID setId = UUID.fromString(setJson.path("id").asText());
        UUID setRevId = UUID.fromString(setJson.path("latestRevisionId").asText());

        // Submit -> Approve -> Publish
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + setRevId + "/submit")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_REVIEW"));

        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + setRevId + "/approve")
                                .with(user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new ReviewDecisionRequest("Duyệt bộ luyện"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        MvcResult publishResult =
                mockMvc.perform(
                                post("/api/v1/admin/content/revisions/" + setRevId + "/publish")
                                        .with(user(adminPrincipal()))
                                        .with(csrf()))
                        .andReturn();
        assertThat(publishResult.getResponse().getStatus())
                .as(publishResult.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(
                        objectMapper
                                .readTree(publishResult.getResponse().getContentAsString())
                                .path("status")
                                .asText())
                .isEqualTo("PUBLISHED");

        // Verify Practice Set Projection
        PracticeSet pSet = practiceSetRepository.findById(setId).orElseThrow();
        assertThat(pSet.getSelectionMode()).isEqualTo("FIXED");
        assertThat(pSet.getTimeLimitSeconds()).isEqualTo(1200);
        assertThat(pSet.getCategory()).isEqualTo("TOEIC");

        // Verify Set Items Projection
        var items = setItemRepository.findBySetIdOrderByPositionAsc(setId);
        assertThat(items).hasSize(2);
        assertThat(items.get(0).getQuestionId()).isEqualTo(standaloneQ);
        assertThat(items.get(0).getGroupId()).isNull();
        assertThat(items.get(1).getQuestionId()).isNull();
        assertThat(items.get(1).getGroupId()).isEqualTo(groupId);

        // Verify Dependencies
        var setDeps = dependencyRepository.findByIdParentRevisionId(setRevId);
        assertThat(setDeps).hasSize(2);
        assertThat(setDeps)
                .anyMatch(
                        d ->
                                d.getId().getRelationKey().equals("set_question:" + standaloneQ)
                                        && d.getChildRevisionId() != null);
        assertThat(setDeps)
                .anyMatch(
                        d ->
                                d.getId().getRelationKey().equals("set_group:" + groupId)
                                        && d.getChildRevisionId().equals(groupRevId));
        assertThat(setDeps)
                .anyMatch(
                        d ->
                                d.getId().getRelationKey().equals("set_question:" + standaloneQ)
                                        && d.getChildRevisionId()
                                                .equals(latestPublishedRevision(standaloneQ)));
        var groupItems = setItemRepository.findBySetIdOrderByPositionAsc(setId);
        assertThat(groupItems).extracting(item -> item.getPosition()).containsExactly(1, 2);
    }

    @Test
    @DisplayName("Practice Set DYNAMIC mode: cấu hình selection_config, không nhận entries")
    void practiceSetDynamic_publish_successAndProjections() throws Exception {
        Map<String, Object> setData = new LinkedHashMap<>();
        setData.put("category", "TOEIC");
        setData.put("part_number", 5);
        setData.put("title", "Daily Part 5 Dynamic Reading Challenge");
        setData.put("selection_mode", "DYNAMIC");
        setData.put("time_limit_seconds", 900);
        setData.put(
                "selection_config",
                Map.of(
                        "version", 1,
                        "question_count", 20,
                        "difficulty", "MEDIUM"));

        CreatePracticeSetDraftRequest setReq =
                new CreatePracticeSetDraftRequest(
                        setData, List.of(activeSource.getId()), "Dynamic practice set");

        MvcResult setRes =
                mockMvc.perform(
                                post("/api/v1/admin/content/practice-sets")
                                        .with(user(adminPrincipal()))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(setReq)))
                        .andExpect(status().isCreated())
                        .andReturn();

        JsonNode setJson = objectMapper.readTree(setRes.getResponse().getContentAsString());
        UUID setId = UUID.fromString(setJson.path("id").asText());
        UUID setRevId = UUID.fromString(setJson.path("latestRevisionId").asText());

        // Submit -> Approve -> Publish
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + setRevId + "/submit")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());

        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + setRevId + "/approve")
                                .with(user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new ReviewDecisionRequest("Duyệt Dynamic"))))
                .andExpect(status().isOk());

        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + setRevId + "/publish")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());

        PracticeSet pSet = practiceSetRepository.findById(setId).orElseThrow();
        assertThat(pSet.getSelectionMode()).isEqualTo("DYNAMIC");
        assertThat(pSet.getSelectionConfig()).contains("question_count");

        var items = setItemRepository.findBySetIdOrderByPositionAsc(setId);
        assertThat(items).isEmpty();
    }

    @Test
    @DisplayName(
            "Validation rejections cho Practice Sets: XOR constraint, mixed mode config/entries")
    void practiceSetValidationRejections() throws Exception {
        // 1. FIXED mode with selection_config
        Map<String, Object> fixedWithConfig = new LinkedHashMap<>();
        fixedWithConfig.put("category", "TOEIC");
        fixedWithConfig.put("title", "Invalid Fixed");
        fixedWithConfig.put("selection_mode", "FIXED");
        fixedWithConfig.put("selection_config", Map.of("count", 10));
        fixedWithConfig.put(
                "entries",
                List.of(
                        Map.of(
                                "position", 1,
                                "question_id", UUID.randomUUID().toString(),
                                "pinned_revision_id", UUID.randomUUID().toString())));

        assertSetValidationFails(
                fixedWithConfig, "selection_mode FIXED không nhận selection_config.");

        // 2. DYNAMIC mode with entries
        Map<String, Object> dynamicWithEntries = new LinkedHashMap<>();
        dynamicWithEntries.put("category", "TOEIC");
        dynamicWithEntries.put("title", "Invalid Dynamic");
        dynamicWithEntries.put("selection_mode", "DYNAMIC");
        dynamicWithEntries.put(
                "entries",
                List.of(
                        Map.of(
                                "position", 1,
                                "question_id", UUID.randomUUID().toString(),
                                "pinned_revision_id", UUID.randomUUID().toString())));

        assertSetValidationFails(dynamicWithEntries, "selection_mode DYNAMIC không nhận entries.");

        // 3. Entry with both question_id and group_id
        Map<String, Object> nonXorEntry = new LinkedHashMap<>();
        nonXorEntry.put("category", "TOEIC");
        nonXorEntry.put("title", "Invalid Non XOR");
        nonXorEntry.put("selection_mode", "FIXED");
        nonXorEntry.put(
                "entries",
                List.of(
                        Map.of(
                                "position", 1,
                                "question_id", UUID.randomUUID().toString(),
                                "group_id", UUID.randomUUID().toString(),
                                "pinned_revision_id", UUID.randomUUID().toString())));

        assertSetValidationFails(
                nonXorEntry, "Mỗi entry phải chứa đúng một trong question_id hoặc group_id (XOR).");

        Map<String, Object> unsupportedDynamicVersion = new LinkedHashMap<>();
        unsupportedDynamicVersion.put("category", "TOEIC");
        unsupportedDynamicVersion.put("part_number", 5);
        unsupportedDynamicVersion.put("title", "Unsupported Dynamic version");
        unsupportedDynamicVersion.put("selection_mode", "DYNAMIC");
        unsupportedDynamicVersion.put(
                "selection_config", Map.of("version", 2, "part_number", 5, "question_count", 20));
        assertSetValidationFails(
                unsupportedDynamicVersion,
                "selection_config version không được hỗ trợ cho category.");

        Map<String, Object> mixedDynamicFilters = new LinkedHashMap<>();
        mixedDynamicFilters.put("category", "TOEIC");
        mixedDynamicFilters.put("part_number", 5);
        mixedDynamicFilters.put("title", "Mixed Dynamic filters");
        mixedDynamicFilters.put("selection_mode", "DYNAMIC");
        mixedDynamicFilters.put(
                "selection_config",
                Map.of(
                        "version",
                        1,
                        "question_count",
                        20,
                        "topic_ids",
                        List.of(UUID.randomUUID())));
        assertSetValidationFails(
                mixedDynamicFilters,
                "TOEIC DYNAMIC yêu cầu Part trên set, không nhận deck/topics.");
    }

    private void assertSetValidationFails(Map<String, Object> data, String expectedMessage)
            throws Exception {
        CreatePracticeSetDraftRequest req =
                new CreatePracticeSetDraftRequest(
                        data, List.of(activeSource.getId()), "Validation test");
        MvcResult res =
                mockMvc.perform(
                                post("/api/v1/admin/content/practice-sets")
                                        .with(user(adminPrincipal()))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(req)))
                        .andExpect(status().isCreated())
                        .andReturn();

        JsonNode json = objectMapper.readTree(res.getResponse().getContentAsString());
        UUID revId = UUID.fromString(json.path("latestRevisionId").asText());

        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + revId + "/validate")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors[0].message").value(expectedMessage));

        // Submit -> Approve -> Publish fails with 400
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + revId + "/submit")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());

        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + revId + "/approve")
                                .with(user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new ReviewDecisionRequest("Duyệt"))))
                .andExpect(status().isOk());

        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + revId + "/publish")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(expectedMessage));
    }

    @Test
    @DisplayName("Quyền truy cập và bảo mật: 401 unauthenticated và 403 non-admin cho Group & Set")
    void securityConstraintsOnAuthoringEndpoints() throws Exception {
        CreateQuestionGroupDraftRequest groupReq =
                new CreateQuestionGroupDraftRequest(Map.of("category", "TOEIC"), null, null);
        CreatePracticeSetDraftRequest setReq =
                new CreatePracticeSetDraftRequest(Map.of("category", "TOEIC"), null, null);

        // Anonymous -> 401
        mockMvc.perform(
                        post("/api/v1/admin/content/question-groups")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(groupReq)))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(
                        post("/api/v1/admin/content/practice-sets")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(setReq)))
                .andExpect(status().isUnauthorized());

        // Learner -> 403
        mockMvc.perform(
                        post("/api/v1/admin/content/question-groups")
                                .with(user(learnerPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(groupReq)))
                .andExpect(status().isForbidden());

        mockMvc.perform(
                        post("/api/v1/admin/content/practice-sets")
                                .with(user(learnerPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(setReq)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName(
            "Question Group publish với child APPROVED: tự động publish child và tạo projection")
    void toeicQuestionGroup_publishWithApprovedChild_success() throws Exception {
        UUID q1 = createApprovedQuestion("TOEIC", 3, "Where is the office located?");
        UUID q2 = createApprovedQuestion("TOEIC", 3, "What time does the meeting start?");
        UUID q3 = createApprovedQuestion("TOEIC", 3, "Who will bring the projector?");

        MediaAsset audioMedia = createReadyMedia("AUDIO", "audio/mpeg");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("category", "TOEIC");
        data.put("part_number", 3);
        data.put("group_type", "CONVERSATION");
        data.put("title", "Office Meeting Discussion");
        data.put(
                "materials",
                List.of(
                        Map.of(
                                "position",
                                1,
                                "material_type",
                                "AUDIO",
                                "usage_role",
                                "STIMULUS",
                                "media_id",
                                audioMedia.getId().toString())));
        data.put(
                "children",
                List.of(
                        Map.of("question_id", q1.toString(), "position", 1),
                        Map.of("question_id", q2.toString(), "position", 2),
                        Map.of("question_id", q3.toString(), "position", 3)));

        CreateQuestionGroupDraftRequest createReq =
                new CreateQuestionGroupDraftRequest(
                        data, List.of(activeSource.getId()), "Group with approved children");

        MvcResult createRes =
                mockMvc.perform(
                                post("/api/v1/admin/content/question-groups")
                                        .with(user(adminPrincipal()))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(createReq)))
                        .andExpect(status().isCreated())
                        .andReturn();

        UUID groupId =
                UUID.fromString(
                        objectMapper
                                .readTree(createRes.getResponse().getContentAsString())
                                .path("id")
                                .asText());
        UUID revId =
                UUID.fromString(
                        objectMapper
                                .readTree(createRes.getResponse().getContentAsString())
                                .path("latestRevisionId")
                                .asText());

        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + revId + "/submit")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + revId + "/approve")
                                .with(user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new ReviewDecisionRequest("Duyệt"))))
                .andExpect(status().isOk());
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + revId + "/publish")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());

        // Verify child questions are now published and projected
        var q1Entity = questionRepository.findById(q1).orElseThrow();
        assertThat(q1Entity.getGroupId()).isEqualTo(groupId);
        assertThat(q1Entity.isInCurrentGroup()).isTrue();
    }

    private UUID createApprovedQuestion(String category, Integer partNumber, String prompt)
            throws Exception {
        int optCount = (partNumber != null && partNumber == 2) ? 3 : 4;
        List<Map<String, Object>> options = makeOptions(optCount);
        String correctId = (String) options.get(0).get("id");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("category", category);
        data.put("part_number", partNumber);
        data.put("question_type_code", "SINGLE_CHOICE");
        data.put("practice_mode", "STANDARD");
        data.put("difficulty", "MEDIUM");
        data.put("prompt", prompt);
        data.put("options", options);
        data.put("answer_key", Map.of("correctOptionId", correctId));
        data.put("explanation", "Explanation for " + prompt);
        data.put("materials", List.of());
        data.put("topic_ids", List.of());
        data.put("word_sense_ids", List.of());

        CreateQuestionDraftRequest createReq =
                new CreateQuestionDraftRequest(
                        data, List.of(activeSource.getId()), "Tạo câu hỏi approved: " + prompt);
        MvcResult res =
                mockMvc.perform(
                                post("/api/v1/admin/content/questions")
                                        .with(user(adminPrincipal()))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(createReq)))
                        .andExpect(status().isCreated())
                        .andReturn();

        JsonNode json = objectMapper.readTree(res.getResponse().getContentAsString());
        UUID itemId = UUID.fromString(json.path("id").asText());
        UUID revId = UUID.fromString(json.path("latestRevisionId").asText());

        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + revId + "/submit")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + revId + "/approve")
                                .with(user(adminPrincipal()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsString(
                                                new ReviewDecisionRequest("Duyệt"))))
                .andExpect(status().isOk());

        return itemId;
    }

    @Test
    @DisplayName("Practice Set DYNAMIC thiếu version trong selection_config bị từ chối 400")
    void practiceSetDynamic_missingVersion_rejected() throws Exception {
        Map<String, Object> setData = new LinkedHashMap<>();
        setData.put("category", "TOEIC");
        setData.put("part_number", 5);
        setData.put("title", "Dynamic set without version");
        setData.put("selection_mode", "DYNAMIC");
        setData.put("time_limit_seconds", 900);
        setData.put("selection_config", Map.of("version", 1, "part_number", 5));

        CreatePracticeSetDraftRequest setReq =
                new CreatePracticeSetDraftRequest(
                        setData, List.of(activeSource.getId()), "Dynamic practice set");

        MvcResult res =
                mockMvc.perform(
                                post("/api/v1/admin/content/practice-sets")
                                        .with(user(adminPrincipal()))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(setReq)))
                        .andExpect(status().isCreated())
                        .andReturn();

        UUID revId =
                UUID.fromString(
                        objectMapper
                                .readTree(res.getResponse().getContentAsString())
                                .path("latestRevisionId")
                                .asText());

        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + revId + "/validate")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(
                        jsonPath("$.errors[0].message")
                                .value("selection_config.question_count phải từ 1 đến 100."));
    }

    @Test
    @DisplayName("Practice Set FIXED từ chối entry trỏ câu hỏi đang thuộc Question Group")
    void fixedSetRejectsGroupedChild() throws Exception {
        UUID q1 = createAndPublishQuestion("TOEIC", 3, "Grouped question 1?");
        UUID q2 = createAndPublishQuestion("TOEIC", 3, "Grouped question 2?");
        UUID q3 = createAndPublishQuestion("TOEIC", 3, "Grouped question 3?");
        UUID q1RevId = latestPublishedRevision(q1);

        MediaAsset audioMedia = createReadyMedia("AUDIO", "audio/mpeg");
        Map<String, Object> groupData = new LinkedHashMap<>();
        groupData.put("category", "TOEIC");
        groupData.put("part_number", 3);
        groupData.put("group_type", "CONVERSATION");
        groupData.put("title", "Group owning q1");
        groupData.put(
                "materials",
                List.of(
                        Map.of(
                                "position",
                                1,
                                "material_type",
                                "AUDIO",
                                "usage_role",
                                "STIMULUS",
                                "media_id",
                                audioMedia.getId().toString())));
        groupData.put(
                "children",
                List.of(
                        Map.of("question_id", q1.toString(), "position", 1),
                        Map.of("question_id", q2.toString(), "position", 2),
                        Map.of("question_id", q3.toString(), "position", 3)));

        MvcResult groupRes =
                mockMvc.perform(
                                post("/api/v1/admin/content/question-groups")
                                        .with(user(adminPrincipal()))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        new CreateQuestionGroupDraftRequest(
                                                                groupData,
                                                                List.of(activeSource.getId()),
                                                                "Group"))))
                        .andExpect(status().isCreated())
                        .andReturn();
        UUID groupRevId =
                UUID.fromString(
                        objectMapper
                                .readTree(groupRes.getResponse().getContentAsString())
                                .path("latestRevisionId")
                                .asText());
        submitAndApprove(groupRevId);
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + groupRevId + "/publish")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());

        Map<String, Object> setData = new LinkedHashMap<>();
        setData.put("category", "TOEIC");
        setData.put("part_number", 3);
        setData.put("title", "FIXED set with grouped child");
        setData.put("selection_mode", "FIXED");
        setData.put("time_limit_seconds", 600);
        setData.put(
                "entries",
                List.of(
                        Map.of(
                                "position", 1,
                                "question_id", q1.toString(),
                                "pinned_revision_id", q1RevId.toString())));

        MvcResult setRes =
                mockMvc.perform(
                                post("/api/v1/admin/content/practice-sets")
                                        .with(user(adminPrincipal()))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        new CreatePracticeSetDraftRequest(
                                                                setData,
                                                                List.of(activeSource.getId()),
                                                                "Set with grouped child"))))
                        .andExpect(status().isCreated())
                        .andReturn();
        UUID setId =
                UUID.fromString(
                        objectMapper
                                .readTree(setRes.getResponse().getContentAsString())
                                .path("id")
                                .asText());
        UUID setRevId =
                UUID.fromString(
                        objectMapper
                                .readTree(setRes.getResponse().getContentAsString())
                                .path("latestRevisionId")
                                .asText());
        submitAndApprove(setRevId);

        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + setRevId + "/publish")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.detail")
                                .value(
                                        org.hamcrest.Matchers.containsString(
                                                "FIXED entry cannot reference a grouped child question")));

        assertThat(practiceSetRepository.findById(setId)).isEmpty();
    }

    @Test
    @DisplayName("Practice Set FIXED đếm số câu con của Group ở giới hạn 100 hợp lệ và từ chối 101")
    void fixedSetCountsPinnedGroupChildrenAtLimit() throws Exception {
        UUID gq1 = createAndPublishQuestion("TOEIC", 3, "Limit group q1?");
        UUID gq2 = createAndPublishQuestion("TOEIC", 3, "Limit group q2?");
        UUID gq3 = createAndPublishQuestion("TOEIC", 3, "Limit group q3?");
        MediaAsset audioMedia = createReadyMedia("AUDIO", "audio/mpeg");

        Map<String, Object> groupData = new LinkedHashMap<>();
        groupData.put("category", "TOEIC");
        groupData.put("part_number", 3);
        groupData.put("group_type", "CONVERSATION");
        groupData.put("title", "Limit Group 3 questions");
        groupData.put(
                "materials",
                List.of(
                        Map.of(
                                "position",
                                1,
                                "material_type",
                                "AUDIO",
                                "usage_role",
                                "STIMULUS",
                                "media_id",
                                audioMedia.getId().toString())));
        groupData.put(
                "children",
                List.of(
                        Map.of("question_id", gq1.toString(), "position", 1),
                        Map.of("question_id", gq2.toString(), "position", 2),
                        Map.of("question_id", gq3.toString(), "position", 3)));

        MvcResult groupRes =
                mockMvc.perform(
                                post("/api/v1/admin/content/question-groups")
                                        .with(user(adminPrincipal()))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        new CreateQuestionGroupDraftRequest(
                                                                groupData,
                                                                List.of(activeSource.getId()),
                                                                "Limit group"))))
                        .andExpect(status().isCreated())
                        .andReturn();
        UUID groupId =
                UUID.fromString(
                        objectMapper
                                .readTree(groupRes.getResponse().getContentAsString())
                                .path("id")
                                .asText());
        UUID groupRevId =
                UUID.fromString(
                        objectMapper
                                .readTree(groupRes.getResponse().getContentAsString())
                                .path("latestRevisionId")
                                .asText());
        submitAndApprove(groupRevId);
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + groupRevId + "/publish")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());

        List<Map<String, Object>> entries100 = new ArrayList<>();
        entries100.add(
                Map.of(
                        "position", 1,
                        "group_id", groupId.toString(),
                        "pinned_revision_id", groupRevId.toString()));

        for (int i = 2; i <= 98; i++) {
            UUID qId = createAndPublishQuestion("TOEIC", 5, "Standalone Q " + i + "?");
            UUID qRevId = latestPublishedRevision(qId);
            entries100.add(
                    Map.of(
                            "position", i,
                            "question_id", qId.toString(),
                            "pinned_revision_id", qRevId.toString()));
        }

        // 3 (group) + 97 (standalone) = 100 -> OK
        Map<String, Object> set100Data = new LinkedHashMap<>();
        set100Data.put("category", "TOEIC");
        set100Data.put("title", "Set 100 questions");
        set100Data.put("selection_mode", "FIXED");
        set100Data.put("entries", entries100);

        MvcResult set100Res =
                mockMvc.perform(
                                post("/api/v1/admin/content/practice-sets")
                                        .with(user(adminPrincipal()))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        new CreatePracticeSetDraftRequest(
                                                                set100Data,
                                                                List.of(activeSource.getId()),
                                                                "Set 100"))))
                        .andExpect(status().isCreated())
                        .andReturn();
        UUID set100Id =
                UUID.fromString(
                        objectMapper
                                .readTree(set100Res.getResponse().getContentAsString())
                                .path("id")
                                .asText());
        UUID set100RevId =
                UUID.fromString(
                        objectMapper
                                .readTree(set100Res.getResponse().getContentAsString())
                                .path("latestRevisionId")
                                .asText());
        submitAndApprove(set100RevId);
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + set100RevId + "/publish")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isOk());
        assertThat(practiceSetRepository.findById(set100Id)).isPresent();

        // Add 1 more -> 101 questions -> 400
        UUID extraQ = createAndPublishQuestion("TOEIC", 5, "Standalone Q 99?");
        UUID extraQRev = latestPublishedRevision(extraQ);
        List<Map<String, Object>> entries101 = new ArrayList<>(entries100);
        entries101.add(
                Map.of(
                        "position", 99,
                        "question_id", extraQ.toString(),
                        "pinned_revision_id", extraQRev.toString()));

        Map<String, Object> set101Data = new LinkedHashMap<>();
        set101Data.put("category", "TOEIC");
        set101Data.put("title", "Set 101 questions");
        set101Data.put("selection_mode", "FIXED");
        set101Data.put("entries", entries101);

        MvcResult set101Res =
                mockMvc.perform(
                                post("/api/v1/admin/content/practice-sets")
                                        .with(user(adminPrincipal()))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        new CreatePracticeSetDraftRequest(
                                                                set101Data,
                                                                List.of(activeSource.getId()),
                                                                "Set 101"))))
                        .andExpect(status().isCreated())
                        .andReturn();
        UUID set101Id =
                UUID.fromString(
                        objectMapper
                                .readTree(set101Res.getResponse().getContentAsString())
                                .path("id")
                                .asText());
        UUID set101RevId =
                UUID.fromString(
                        objectMapper
                                .readTree(set101Res.getResponse().getContentAsString())
                                .path("latestRevisionId")
                                .asText());
        submitAndApprove(set101RevId);
        mockMvc.perform(
                        post("/api/v1/admin/content/revisions/" + set101RevId + "/publish")
                                .with(user(adminPrincipal()))
                                .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.detail")
                                .value("FIXED set cannot exceed 100 pinned questions."));
        assertThat(practiceSetRepository.findById(set101Id)).isEmpty();
    }

    @Test
    @DisplayName("Practice Set DYNAMIC từ chối mixed filter (mode trên GRAMMAR/TOEIC)")
    void practiceSetDynamic_rejectsMixedModeFilter() throws Exception {
        Map<String, Object> grammarData = new LinkedHashMap<>();
        grammarData.put("category", "GRAMMAR");
        grammarData.put("title", "Grammar dynamic with vocab mode");
        grammarData.put("selection_mode", "DYNAMIC");
        grammarData.put(
                "selection_config",
                Map.of("version", 1, "question_count", 10, "mode", "FLASHCARD"));
        assertSetValidationFails(grammarData, "Grammar DYNAMIC không nhận mode.");

        Map<String, Object> toeicData = new LinkedHashMap<>();
        toeicData.put("category", "TOEIC");
        toeicData.put("part_number", 5);
        toeicData.put("title", "TOEIC dynamic with vocab mode");
        toeicData.put("selection_mode", "DYNAMIC");
        toeicData.put(
                "selection_config",
                Map.of("version", 1, "question_count", 10, "mode", "MULTIPLE_CHOICE"));
        assertSetValidationFails(toeicData, "TOEIC DYNAMIC không nhận mode.");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName(
            "Cạnh tranh publish Group và Practice Set FIXED: không deadlock, chặn đúng thứ tự khóa")
    void groupAndFixedSetConcurrency_respectsLockOrderWithoutDeadlock() throws Exception {
        UUID q1 = createAndPublishQuestion("TOEIC", 3, "Concurrent q1?");
        UUID q2 = createAndPublishQuestion("TOEIC", 3, "Concurrent q2?");
        UUID q3 = createAndPublishQuestion("TOEIC", 3, "Concurrent q3?");
        UUID q1RevId = latestPublishedRevision(q1);
        MediaAsset audioMedia = createReadyMedia("AUDIO", "audio/mpeg");

        Map<String, Object> groupData = new LinkedHashMap<>();
        groupData.put("category", "TOEIC");
        groupData.put("part_number", 3);
        groupData.put("group_type", "CONVERSATION");
        groupData.put("title", "Concurrent Group");
        groupData.put(
                "materials",
                List.of(
                        Map.of(
                                "position",
                                1,
                                "material_type",
                                "AUDIO",
                                "usage_role",
                                "STIMULUS",
                                "media_id",
                                audioMedia.getId().toString())));
        groupData.put(
                "children",
                List.of(
                        Map.of("question_id", q1.toString(), "position", 1),
                        Map.of("question_id", q2.toString(), "position", 2),
                        Map.of("question_id", q3.toString(), "position", 3)));

        MvcResult groupRes =
                mockMvc.perform(
                                post("/api/v1/admin/content/question-groups")
                                        .with(user(adminPrincipal()))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        new CreateQuestionGroupDraftRequest(
                                                                groupData,
                                                                List.of(activeSource.getId()),
                                                                "Concurrent group"))))
                        .andExpect(status().isCreated())
                        .andReturn();
        UUID groupId =
                UUID.fromString(
                        objectMapper
                                .readTree(groupRes.getResponse().getContentAsString())
                                .path("id")
                                .asText());
        UUID groupRevId =
                UUID.fromString(
                        objectMapper
                                .readTree(groupRes.getResponse().getContentAsString())
                                .path("latestRevisionId")
                                .asText());
        submitAndApprove(groupRevId);

        Map<String, Object> setData = new LinkedHashMap<>();
        setData.put("category", "TOEIC");
        setData.put("part_number", 3);
        setData.put("title", "Concurrent Set");
        setData.put("selection_mode", "FIXED");
        setData.put(
                "entries",
                List.of(
                        Map.of(
                                "position", 1,
                                "question_id", q1.toString(),
                                "pinned_revision_id", q1RevId.toString())));

        MvcResult setRes =
                mockMvc.perform(
                                post("/api/v1/admin/content/practice-sets")
                                        .with(user(adminPrincipal()))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        new CreatePracticeSetDraftRequest(
                                                                setData,
                                                                List.of(activeSource.getId()),
                                                                "Concurrent set"))))
                        .andExpect(status().isCreated())
                        .andReturn();
        UUID setId =
                UUID.fromString(
                        objectMapper
                                .readTree(setRes.getResponse().getContentAsString())
                                .path("id")
                                .asText());
        UUID setRevId =
                UUID.fromString(
                        objectMapper
                                .readTree(setRes.getResponse().getContentAsString())
                                .path("latestRevisionId")
                                .asText());
        submitAndApprove(setRevId);

        java.util.concurrent.CountDownLatch lockHeld = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseLock =
                new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger holderPid =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(2);

        try {
            var holderFuture =
                    pool.submit(
                            () ->
                                    transactionTemplate.execute(
                                            status -> {
                                                Integer pid =
                                                        jdbcTemplate.queryForObject(
                                                                "SELECT pg_backend_pid()",
                                                                Integer.class);
                                                holderPid.set(pid != null ? pid : 0);
                                                itemRepository.findByIdForUpdate(q1).orElseThrow();
                                                lockHeld.countDown();
                                                try {
                                                    releaseLock.await(
                                                            10,
                                                            java.util.concurrent.TimeUnit.SECONDS);
                                                } catch (InterruptedException e) {
                                                    Thread.currentThread().interrupt();
                                                }
                                                return null;
                                            }));

            assertThat(lockHeld.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

            var setFuture =
                    pool.submit(
                            () ->
                                    mockMvc.perform(
                                                    post("/api/v1/admin/content/revisions/"
                                                                    + setRevId
                                                                    + "/publish")
                                                            .with(user(adminPrincipal()))
                                                            .with(csrf()))
                                            .andReturn()
                                            .getResponse()
                                            .getStatus());

            // Wait until Set publish transaction is actually blocked by holderPid on PostgreSQL
            long deadline = System.currentTimeMillis() + 5000;
            boolean blocked = false;
            while (System.currentTimeMillis() < deadline) {
                Integer blockingCount =
                        jdbcTemplate.queryForObject(
                                """
                                SELECT count(*) FROM pg_stat_activity
                                WHERE state = 'active'
                                  AND ? = ANY(pg_blocking_pids(pid))
                                """,
                                Integer.class,
                                holderPid.get());
                if (blockingCount != null && blockingCount > 0) {
                    blocked = true;
                    break;
                }
                Thread.sleep(50);
            }
            assertThat(blocked).isTrue();

            releaseLock.countDown();
            holderFuture.get(5, java.util.concurrent.TimeUnit.SECONDS);
            int setStatus = setFuture.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(setStatus).isEqualTo(200);

            // Second order: now Group publishes after Set; succeeds and assigns q1 to group
            mockMvc.perform(
                            post("/api/v1/admin/content/revisions/" + groupRevId + "/publish")
                                    .with(user(adminPrincipal()))
                                    .with(csrf()))
                    .andExpect(status().isOk());
            assertThat(questionRepository.findById(q1).orElseThrow().isInCurrentGroup()).isTrue();
        } finally {
            releaseLock.countDown();
            pool.shutdownNow();
        }
    }
}
