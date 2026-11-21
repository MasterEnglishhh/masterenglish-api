package com.masterenglish.api.modules.practice.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.masterenglish.api.TestcontainersConfiguration;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.common.events.ContentEvents.RevisionPublished;
import com.masterenglish.api.modules.content.repository.ContentRevisionRepository;
import com.masterenglish.api.modules.content.service.ContentAuthoringService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * QUESTION publication through the real content pipeline (create draft, submit, approve, publish).
 * Not @Transactional: the rollback case needs the publication transaction to end on its own.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class QuestionPublicationIntegrationTests {

    @Autowired private ContentAuthoringService authoring;
    @Autowired private ContentRevisionRepository revisions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private TransactionTemplate transactions;

    @MockitoSpyBean private QuestionProjectionListener projection;

    private UUID author;
    private UUID source;

    @BeforeEach
    void setUp() {
        author = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, email, password_hash, first_name, last_name, status, email_verified_at)"
                        + " VALUES (?, ?, ?, ?, ?, 'ACTIVE', NOW())",
                author,
                "question-author-" + suffix() + "@example.com",
                "$2a$12$e8YkC6XQ8XbY0u9VqT6eIeaFmP/2eZ.z1c8yU2MvX3mN.rLw5QkOe",
                "Question",
                "Author");
        jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES (?,2)", author);
        source = source(true);
    }

    @Test
    void validQuestionPublishesThroughContentAndIsProjected() throws Exception {
        UUID topic = publishTopic();
        UUID item = UUID.randomUUID();
        UUID correct = UUID.randomUUID();
        UUID wrong = UUID.randomUUID();

        UUID revision = publish(approve(draft(item, questionJson(topic, correct, wrong), source)));

        assertThat(publishedRevision(item)).isEqualTo(revision);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT difficulty || '/' || practice_mode || '/' || question_type_code"
                                        + " FROM questions WHERE id = ?",
                                String.class,
                                item))
                .isEqualTo("HARD/STANDARD/SINGLE_CHOICE");
        assertThat(options(item))
                .containsExactly(
                        Map.of("id", correct, "position", 1, "is_correct", true),
                        Map.of("id", wrong, "position", 2, "is_correct", false));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM question_topics WHERE question_id = ? AND topic_id = ?",
                                Integer.class,
                                item,
                                topic))
                .isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"question_type_code", "practice_mode", "difficulty"})
    void draftMayOmitARequiredFieldButPublicationIsRefused(String field) throws Exception {
        UUID topic = publishTopic();
        UUID item = UUID.randomUUID();
        Map<String, Object> data = questionData(topic, UUID.randomUUID(), UUID.randomUUID());
        data.remove(field);
        UUID approved = approve(draft(item, mapper.writeValueAsString(data), source));

        assertRefused(approved, ErrorCode.VALIDATION_ERROR, field + " là bắt buộc khi publish.");
        assertThat(revisionStatus(approved)).isEqualTo("APPROVED");
        assertThat(count("questions", "id", item)).isZero();
    }

    @Test
    void answerKeyOutsideTheOptionsIsRefused() throws Exception {
        UUID topic = publishTopic();
        UUID item = UUID.randomUUID();
        Map<String, Object> data = questionData(topic, UUID.randomUUID(), UUID.randomUUID());
        data.put("answer_key", Map.of("correctOptionId", UUID.randomUUID().toString()));
        UUID approved = approve(draft(item, mapper.writeValueAsString(data), source));

        assertRefused(
                approved,
                ErrorCode.VALIDATION_ERROR,
                "answer_key.correctOptionId phải là một lựa chọn của câu hỏi.");
        assertThat(count("questions", "id", item)).isZero();
    }

    @Test
    void publicationWithoutADeclaredSourceIsRefused() throws Exception {
        UUID topic = publishTopic();
        UUID item = UUID.randomUUID();
        UUID approved =
                approve(
                        draft(
                                item,
                                questionJson(topic, UUID.randomUUID(), UUID.randomUUID()),
                                null));

        assertRefused(
                approved, ErrorCode.VALIDATION_ERROR, "Published content needs a declared source.");
        assertThat(count("questions", "id", item)).isZero();
    }

    @Test
    void publicationWithASourceThatHasNoPermissionIsRefused() throws Exception {
        UUID topic = publishTopic();
        UUID item = UUID.randomUUID();
        UUID noPermission = source(false);
        UUID approved =
                approve(
                        draft(
                                item,
                                questionJson(topic, UUID.randomUUID(), UUID.randomUUID()),
                                noPermission));

        assertRefused(
                approved,
                ErrorCode.VALIDATION_ERROR,
                "Active source and declared permission required.");
        assertThat(count("questions", "id", item)).isZero();
    }

    @Test
    void topicThatIsNotPublishedIsRefused() throws Exception {
        UUID item = UUID.randomUUID();
        UUID approved =
                approve(
                        draft(
                                item,
                                questionJson(
                                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()),
                                source));

        assertRefused(
                approved,
                ErrorCode.VALIDATION_ERROR,
                "topic_ids phải tham chiếu chủ điểm đã xuất bản.");
        assertThat(count("questions", "id", item)).isZero();
    }

    @Test
    void optionIdOwnedByAnotherQuestionIsRefused() throws Exception {
        UUID topic = publishTopic();
        UUID sharedOption = UUID.randomUUID();
        publish(
                approve(
                        draft(
                                UUID.randomUUID(),
                                questionJson(topic, sharedOption, UUID.randomUUID()),
                                source)));

        UUID item = UUID.randomUUID();
        UUID approved =
                approve(draft(item, questionJson(topic, sharedOption, UUID.randomUUID()), source));

        assertRefused(approved, ErrorCode.VALIDATION_ERROR, "options.id đã thuộc câu hỏi khác.");
        assertThat(count("questions", "id", item)).isZero();
    }

    @Test
    void projectionFailureRollsBackThePublication() throws Exception {
        UUID topic = publishTopic();
        UUID item = UUID.randomUUID();
        UUID approved =
                approve(
                        draft(
                                item,
                                questionJson(topic, UUID.randomUUID(), UUID.randomUUID()),
                                source));
        // The listener is @Transactional(MANDATORY); stubbing it through the proxy needs a
        // transaction.
        transactions.executeWithoutResult(
                status ->
                        doThrow(new IllegalStateException("projection failure"))
                                .when(projection)
                                .on(any(RevisionPublished.class)));

        assertThatThrownBy(() -> publish(approved)).isInstanceOf(IllegalStateException.class);

        assertThat(revisionStatus(approved)).isEqualTo("APPROVED");
        assertThat(publishedRevision(item)).isNull();
        assertThat(count("questions", "id", item)).isZero();
        assertThat(count("question_options", "question_id", item)).isZero();
    }

    @Test
    void existingGrammarTopicStillPublishesAfterQuestionSupport() {
        UUID topic = publishTopic();

        assertThat(count("grammar_topics", "id", topic)).isEqualTo(1);
    }

    // ---- helpers ---------------------------------------------------------------------------

    private void assertRefused(UUID approvedRevision, ErrorCode code, String message) {
        assertThatThrownBy(() -> publish(approvedRevision))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> {
                            assertThat(ex.getErrorCode()).isEqualTo(code);
                            assertThat(ex.getMessage()).isEqualTo(message);
                        });
    }

    private UUID publishTopic() {
        UUID item = UUID.randomUUID();
        UUID topicSource = source;
        publish(approve(draft(item, topicJson(), topicSource, "GRAMMAR_TOPIC")));
        return item;
    }

    private UUID source(boolean withPermission) {
        return authoring
                .createSource(
                        UUID.randomUUID(),
                        "Question source",
                        "SELF_AUTHORED",
                        null,
                        "Question author",
                        withPermission ? "SELF_AUTHORED" : null,
                        null,
                        "Question attribution",
                        withPermission ? "Permission granted" : null,
                        author)
                .getId();
    }

    private UUID draft(UUID item, String snapshot, UUID sourceId) {
        return draft(item, snapshot, sourceId, "QUESTION");
    }

    private UUID draft(UUID item, String snapshot, UUID sourceId, String type) {
        authoring.createItemWithDraft(
                item, type, author, null, null, snapshot, "question draft", sourceId);
        return revisions.findByContentItemIdOrderByVersionNoDesc(item).get(0).getId();
    }

    private UUID approve(UUID draftRevision) {
        var inReview = authoring.submitForReview(draftRevision, author);
        return authoring.approve(inReview.getId(), author, "Content verified").getId();
    }

    private UUID publish(UUID approvedRevision) {
        return authoring.publish(approvedRevision, author).getId();
    }

    private String questionJson(UUID topic, UUID correct, UUID wrong) {
        try {
            return mapper.writeValueAsString(questionData(topic, correct, wrong));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Data object of a QUESTION draft (SCHEMA section 4). Callers remove or change fields. */
    private static Map<String, Object> questionData(UUID topic, UUID correct, UUID wrong) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("category", "GRAMMAR");
        data.put("part_number", null);
        data.put("question_type_code", "SINGLE_CHOICE");
        data.put("practice_mode", "STANDARD");
        data.put("difficulty", "HARD");
        data.put("prompt", "She ___ to school every day.");
        data.put("group", null);
        data.put(
                "options",
                List.of(
                        Map.of("id", correct.toString(), "position", 1, "body", "goes"),
                        Map.of("id", wrong.toString(), "position", 2, "body", "go")));
        data.put("answer_key", Map.of("correctOptionId", correct.toString()));
        data.put("explanation", "Third person singular takes -s.");
        data.put("materials", List.of());
        data.put("topic_ids", List.of(topic.toString()));
        data.put("word_sense_ids", List.of());
        return data;
    }

    private String topicJson() {
        return "{\"slug\": \"topic-"
                + suffix()
                + "\", \"name\": \"Topic\", \"description\": \"A and an\","
                + " \"difficulty\": \"BASIC\", \"sort_order\": 1}";
    }

    private UUID publishedRevision(UUID item) {
        return jdbc.queryForObject(
                "SELECT published_revision_id FROM content_items WHERE id = ?", UUID.class, item);
    }

    private String revisionStatus(UUID revision) {
        return jdbc.queryForObject(
                "SELECT status FROM content_revisions WHERE id = ?", String.class, revision);
    }

    private List<Map<String, Object>> options(UUID question) {
        return jdbc.queryForList(
                "SELECT id, position, is_correct FROM question_options WHERE question_id = ? ORDER BY position",
                question);
    }

    private int count(String table, String column, UUID value) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + column + " = ?",
                Integer.class,
                value);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
