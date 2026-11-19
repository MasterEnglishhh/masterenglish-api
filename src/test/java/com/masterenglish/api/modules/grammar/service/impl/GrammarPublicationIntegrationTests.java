package com.masterenglish.api.modules.grammar.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.masterenglish.api.TestcontainersConfiguration;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.modules.content.repository.ContentRevisionRepository;
import com.masterenglish.api.modules.content.service.ContentAuthoringService;
import com.masterenglish.api.modules.grammar.entity.GrammarLesson;
import com.masterenglish.api.modules.grammar.entity.GrammarTopic;
import com.masterenglish.api.modules.grammar.repository.GrammarLessonRepository;
import com.masterenglish.api.modules.grammar.repository.GrammarTopicRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Drives grammar content through the real content lifecycle: draft, review, approve, publish. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class GrammarPublicationIntegrationTests {

    private static final String TOPIC = "GRAMMAR_TOPIC";
    private static final String LESSON = "GRAMMAR_LESSON";

    @Autowired private ContentAuthoringService authoringService;
    @Autowired private ContentRevisionRepository revisionRepository;
    @Autowired private GrammarTopicRepository topics;
    @Autowired private GrammarLessonRepository lessons;
    @Autowired private JdbcTemplate jdbc;

    private UUID adminId;
    private UUID sourceId;

    @BeforeEach
    void setUp() {
        adminId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, email, password_hash, first_name, last_name, status, email_verified_at)"
                        + " VALUES (?, ?, ?, ?, ?, 'ACTIVE', NOW())",
                adminId,
                "grammar-author@example.com",
                "$2a$12$e8YkC6XQ8XbY0u9VqT6eIeaFmP/2eZ.z1c8yU2MvX3mN.rLw5QkOe",
                "Grammar",
                "Author");
        jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES (?,2)", adminId);
        sourceId =
                authoringService
                        .createSource(
                                UUID.randomUUID(),
                                "Grammar test source",
                                "SELF_AUTHORED",
                                null,
                                "Grammar author",
                                "SELF_AUTHORED",
                                null,
                                "Grammar test attribution",
                                "Grammar publication test",
                                adminId)
                        .getId();
    }

    @Test
    void publishedTopicIsProjectedIntoGrammarTopics() {
        UUID topicId = UUID.randomUUID();
        UUID revision = publish(topicId, TOPIC, topicSnapshot("articles-a", "Articles"));

        GrammarTopic topic = topics.findById(topicId).orElseThrow();
        assertThat(topic.getName()).isEqualTo("Articles");
        assertThat(topic.getPublishedRevisionId()).isEqualTo(revision);
    }

    @Test
    void publishedLessonIsProjectedUnderItsTopic() {
        UUID topicId = UUID.randomUUID();
        UUID topicRevision = publish(topicId, TOPIC, topicSnapshot("articles-b", "Articles"));

        UUID lessonId = UUID.randomUUID();
        publish(
                lessonId,
                LESSON,
                lessonSnapshot(
                        topicId, topicRevision, "definite-article", "Use the before nouns."));

        GrammarLesson lesson = lessons.findById(lessonId).orElseThrow();
        assertThat(lesson.getTopicId()).isEqualTo(topicId);
        assertThat(lesson.getTitle()).isEqualTo("Definite article");
        assertThat(lesson.getBody()).contains("Use the before nouns.");
    }

    @Test
    void lessonWithHtmlIsRejectedAndNotProjected() {
        UUID topicId = UUID.randomUUID();
        UUID topicRevision = publish(topicId, TOPIC, topicSnapshot("articles-c", "Articles"));
        UUID lessonId = UUID.randomUUID();

        assertThatThrownBy(
                        () ->
                                publish(
                                        lessonId,
                                        LESSON,
                                        lessonSnapshot(
                                                topicId,
                                                topicRevision,
                                                "unsafe-lesson",
                                                "<b>Use the</b> before nouns.")))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR))
                .hasMessage("HTML is not allowed in grammar content.");
        assertThat(lessons.findById(lessonId)).isEmpty();
    }

    @Test
    void lessonNeedsAPublishedTopic() {
        UUID missingTopic = UUID.randomUUID();

        assertThatThrownBy(
                        () ->
                                publish(
                                        UUID.randomUUID(),
                                        LESSON,
                                        lessonSnapshot(
                                                missingTopic,
                                                UUID.randomUUID(),
                                                "orphan-lesson",
                                                "Orphan text.")))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR))
                .hasMessage("topic_id must reference a published topic.");
    }

    @Test
    void lessonPinnedRevisionMustBePublishedForItsTopic() {
        UUID topicId = UUID.randomUUID();
        publish(topicId, TOPIC, topicSnapshot("articles-d", "Articles"));

        assertThatThrownBy(
                        () ->
                                publish(
                                        UUID.randomUUID(),
                                        LESSON,
                                        lessonSnapshot(
                                                topicId,
                                                UUID.randomUUID(),
                                                "wrong-pin",
                                                "Pinned text.")))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR))
                .hasMessage("pinned_topic_revision_id must be a published revision of that topic.");
    }

    @Test
    void topicSlugIsUniqueAcrossPublishedTopics() {
        publish(UUID.randomUUID(), TOPIC, topicSnapshot("shared-slug", "First"));

        assertThatThrownBy(
                        () ->
                                publish(
                                        UUID.randomUUID(),
                                        TOPIC,
                                        topicSnapshot("shared-slug", "Second")))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR))
                .hasMessage("slug is already used by another topic.");
    }

    /**
     * Creates a draft, moves it through review and approval, and publishes it. Returns the revision
     * id.
     */
    private UUID publish(UUID itemId, String type, String snapshot) {
        authoringService.createItemWithDraft(
                itemId, type, adminId, null, null, snapshot, "grammar draft", sourceId);
        UUID draft =
                revisionRepository.findByContentItemIdOrderByVersionNoDesc(itemId).get(0).getId();
        var inReview = authoringService.submitForReview(draft, adminId);
        var approved = authoringService.approve(inReview.getId(), adminId, "Content verified");
        return authoringService.publish(approved.getId(), adminId).getId();
    }

    private static String topicSnapshot(String slug, String name) {
        return """
                {"schema_version": 1, "content_type": "GRAMMAR_TOPIC", "slug": "%s",
                 "name": "%s", "description": "A and an", "difficulty": "BASIC", "sort_order": 1}
                """
                .formatted(slug, name);
    }

    private static String lessonSnapshot(UUID topicId, UUID pinned, String slug, String text) {
        return """
                {"schema_version": 1, "content_type": "GRAMMAR_LESSON",
                 "topic_id": "%s", "pinned_topic_revision_id": "%s", "title": "Definite article",
                 "slug": "%s", "position": 1, "estimated_minutes": 5,
                 "body": {"blocks": [{"type": "paragraph", "text": "%s"}]}}
                """
                .formatted(topicId, pinned, slug, text);
    }
}
