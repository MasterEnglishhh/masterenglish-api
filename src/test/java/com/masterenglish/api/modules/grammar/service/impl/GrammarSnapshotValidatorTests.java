package com.masterenglish.api.modules.grammar.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.common.events.ContentEvents;
import com.masterenglish.api.common.events.ContentEvents.SnapshotValidationRequested;
import com.masterenglish.api.common.events.EventReply;
import com.masterenglish.api.modules.grammar.entity.GrammarTopic;
import com.masterenglish.api.modules.grammar.repository.GrammarTopicRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GrammarSnapshotValidatorTests {

    private static final String REQUEST = "SnapshotValidationRequested";
    private static final String TOPIC = "GRAMMAR_TOPIC";
    private static final String LESSON = "GRAMMAR_LESSON";

    private final ObjectMapper mapper = new ObjectMapper();
    private final ContentEvents content = mock(ContentEvents.class);
    private final GrammarTopicRepository topics = mock(GrammarTopicRepository.class);
    private final GrammarSnapshotValidator validator =
            new GrammarSnapshotValidator(content, topics);

    @Test
    void otherContentTypesAreSkippedAndLeftUnanswered() {
        for (String type : new String[] {"WORD", "DECK", "QUESTION"}) {
            EventReply<Void> reply = new EventReply<>();
            SnapshotValidationRequested event =
                    new SnapshotValidationRequested(
                            UUID.randomUUID(), type, mapper.createObjectNode(), reply);

            assertThatCode(() -> validator.on(event)).doesNotThrowAnyException();
            assertThatThrownBy(() -> reply.get(REQUEST)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void validTopicIsAnswered() {
        EventReply<Void> reply = new EventReply<>();

        validator.on(new SnapshotValidationRequested(UUID.randomUUID(), TOPIC, topic(), reply));

        assertThat(reply.get(REQUEST)).isNull();
    }

    @Test
    void topicSlugMustBeUrlSafe() {
        ObjectNode topic = topic();
        topic.put("slug", "Bad Slug");

        assertRejected(TOPIC, topic, "slug must use lowercase letters, digits and hyphens.");
    }

    @Test
    void topicSlugMustBeUniqueAcrossTopics() {
        when(topics.existsBySlugAndIdNot(eq("articles"), any(UUID.class))).thenReturn(true);

        assertRejected(TOPIC, topic(), "slug is already used by another topic.");
    }

    @Test
    void topicRejectsHtml() {
        ObjectNode topic = topic();
        topic.put("name", "<b>Articles</b>");

        assertRejected(TOPIC, topic, "HTML is not allowed in grammar content.");
    }

    @Test
    void topicRejectsUnknownFields() {
        ObjectNode topic = topic();
        topic.put("author_note", "not part of the contract");

        assertRejected(TOPIC, topic, "Unsupported snapshot field: author_note");
    }

    @Test
    void validLessonUnderAPublishedTopicIsAnswered() {
        UUID topicId = UUID.randomUUID();
        when(topics.findVisibleTopicById(topicId))
                .thenReturn(Optional.of(mock(GrammarTopic.class)));
        when(content.snapshot(eq(topicId), any(UUID.class), eq(TOPIC), anyBoolean()))
                .thenReturn(Optional.of("{}"));
        EventReply<Void> reply = new EventReply<>();

        validator.on(
                new SnapshotValidationRequested(UUID.randomUUID(), LESSON, lesson(topicId), reply));

        assertThat(reply.get(REQUEST)).isNull();
    }

    @Test
    void lessonNeedsAPublishedTopic() {
        ObjectNode lesson = lesson(UUID.randomUUID());

        assertRejected(LESSON, lesson, "topic_id must reference a published topic.");
    }

    @Test
    void lessonNeedsAPublishedRevisionOfItsTopic() {
        UUID topicId = UUID.randomUUID();
        when(topics.findVisibleTopicById(topicId))
                .thenReturn(Optional.of(mock(GrammarTopic.class)));
        when(content.snapshot(eq(topicId), any(UUID.class), eq(TOPIC), anyBoolean()))
                .thenReturn(Optional.empty());

        assertRejected(
                LESSON,
                lesson(topicId),
                "pinned_topic_revision_id must be a published revision of that topic.");
    }

    @Test
    void lessonRejectsUnknownBlockTypes() {
        UUID topicId = publishedTopic();
        ObjectNode lesson = lesson(topicId);
        blocks(lesson).removeAll();
        blocks(lesson).addObject().put("type", "video").put("url", "https://example.com");

        assertRejected(LESSON, lesson, "Unsupported block type: video");
    }

    @Test
    void lessonRejectsHtmlInParagraphs() {
        UUID topicId = publishedTopic();
        ObjectNode lesson = lesson(topicId);
        blocks(lesson).removeAll();
        blocks(lesson).addObject().put("type", "paragraph").put("text", "<script>x</script>");

        assertRejected(LESSON, lesson, "HTML is not allowed in grammar content.");
    }

    @Test
    void lessonNeedsAtLeastOneBlock() {
        UUID topicId = publishedTopic();
        ObjectNode lesson = lesson(topicId);
        blocks(lesson).removeAll();

        assertRejected(LESSON, lesson, "body.blocks must contain 1 to 100 blocks.");
    }

    @Test
    void lessonRejectsMediaBlocks() {
        UUID topicId = publishedTopic();
        ObjectNode lesson = lesson(topicId);
        blocks(lesson).removeAll();
        blocks(lesson).addObject().put("type", "media").put("media_id", "img-1");

        assertRejected(LESSON, lesson, "Unsupported block type: media");
    }

    @Test
    void lessonRejectsHtmlInTableCells() {
        UUID topicId = publishedTopic();
        ObjectNode lesson = lesson(topicId);
        blocks(lesson).removeAll();
        blocks(lesson).addObject().put("type", "table").putArray("rows").addArray().add("<b>x</b>");

        assertRejected(LESSON, lesson, "HTML is not allowed in grammar content.");
    }

    @Test
    void lessonRejectsUnknownFieldsInBody() {
        UUID topicId = publishedTopic();
        ObjectNode lesson = lesson(topicId);
        ((ObjectNode) lesson.path("body")).put("media", "img-1");

        assertRejected(LESSON, lesson, "Unsupported snapshot field: media");
    }

    @Test
    void lessonRejectsUnknownFieldsInsideABlock() {
        UUID topicId = publishedTopic();
        ObjectNode lesson = lesson(topicId);
        ((ObjectNode) blocks(lesson).get(0)).put("color", "red");

        assertRejected(LESSON, lesson, "Unsupported snapshot field: color");
    }

    @Test
    void lessonAcceptsBodyBelowTheCharacterLimit() {
        UUID topicId = publishedTopic();
        ObjectNode lesson = lesson(topicId);
        blocks(lesson).removeAll();
        blocks(lesson).addObject().put("type", "paragraph").put("text", "a".repeat(50_000));
        blocks(lesson).addObject().put("type", "paragraph").put("text", "b".repeat(40_000));
        EventReply<Void> reply = new EventReply<>();

        validator.on(new SnapshotValidationRequested(UUID.randomUUID(), LESSON, lesson, reply));

        assertThat(reply.get(REQUEST)).isNull();
    }

    @Test
    void lessonRejectsBodyOverOneHundredThousandCharacters() {
        UUID topicId = publishedTopic();
        ObjectNode lesson = lesson(topicId);
        blocks(lesson).removeAll();
        blocks(lesson).addObject().put("type", "paragraph").put("text", "a".repeat(50_000));
        blocks(lesson).addObject().put("type", "paragraph").put("text", "b".repeat(50_000));

        assertRejected(LESSON, lesson, "body must be at most 100000 characters.");
    }

    @Test
    void lessonRejectsNestingDeeperThanSixteenLevels() {
        UUID topicId = publishedTopic();
        ObjectNode lesson = lesson(topicId);
        ObjectNode deep = mapper.createObjectNode();
        JsonNode level = deep;
        for (int i = 0; i < 17; i++) {
            level = ((ObjectNode) level).putObject("n");
        }
        ((ObjectNode) lesson.path("body")).set("extra", deep);

        assertRejected(LESSON, lesson, "body nesting must be at most 16 levels.");
    }

    private void assertRejected(String type, JsonNode data, String message) {
        EventReply<Void> reply = new EventReply<>();
        SnapshotValidationRequested event =
                new SnapshotValidationRequested(UUID.randomUUID(), type, data, reply);

        assertThatThrownBy(() -> validator.on(event))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR))
                .hasMessage(message);
        assertThatThrownBy(() -> reply.get(REQUEST)).isInstanceOf(IllegalStateException.class);
    }

    private UUID publishedTopic() {
        UUID topicId = UUID.randomUUID();
        when(topics.findVisibleTopicById(topicId))
                .thenReturn(Optional.of(mock(GrammarTopic.class)));
        when(content.snapshot(eq(topicId), any(UUID.class), eq(TOPIC), anyBoolean()))
                .thenReturn(Optional.of("{}"));
        return topicId;
    }

    private ObjectNode topic() {
        ObjectNode topic = mapper.createObjectNode();
        topic.put("slug", "articles");
        topic.put("name", "Articles");
        topic.put("description", "A and an");
        topic.put("difficulty", "BASIC");
        topic.put("sort_order", 1);
        return topic;
    }

    private ObjectNode lesson(UUID topicId) {
        ObjectNode lesson = mapper.createObjectNode();
        lesson.put("topic_id", topicId.toString());
        lesson.put("pinned_topic_revision_id", UUID.randomUUID().toString());
        lesson.put("title", "Definite article");
        lesson.put("slug", "definite-article");
        lesson.put("position", 1);
        lesson.put("estimated_minutes", 5);
        ObjectNode body = lesson.putObject("body");
        ArrayNode blocks = body.putArray("blocks");
        blocks.addObject().put("type", "paragraph").put("text", "Use the before a specific noun.");
        return lesson;
    }

    private ArrayNode blocks(ObjectNode lesson) {
        return (ArrayNode) lesson.path("body").path("blocks");
    }
}
