package com.masterenglish.api.modules.grammar.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.common.events.ContentEvents;
import com.masterenglish.api.common.events.ContentEvents.SnapshotValidationRequested;
import com.masterenglish.api.modules.grammar.repository.GrammarTopicRepository;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Validates grammar snapshots before publication. It answers only GRAMMAR_TOPIC and GRAMMAR_LESSON
 * requests; other content types belong to their own modules and are left unanswered here.
 */
@Component
public class GrammarSnapshotValidator {

    static final String TOPIC = "GRAMMAR_TOPIC";
    static final String LESSON = "GRAMMAR_LESSON";

    private static final Pattern SLUG = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Pattern HTML = Pattern.compile("<\\s*/?\\s*[a-zA-Z!]");
    private static final Set<String> DIFFICULTIES = Set.of("BASIC", "INTERMEDIATE", "ADVANCED");
    private static final Set<String> BLOCK_TYPES =
            Set.of("heading", "paragraph", "note", "example", "table");
    private static final int MAX_BLOCKS = 100;
    private static final int MAX_ROWS = 100;
    private static final int MAX_CELLS = 20;
    private static final int MAX_BODY_CHARS = 100_000;
    private static final int MAX_POSITION = 100_000;
    private static final int MAX_ESTIMATED_MINUTES = 600;

    private final ContentEvents content;
    private final GrammarTopicRepository topics;

    public GrammarSnapshotValidator(ContentEvents content, GrammarTopicRepository topics) {
        this.content = content;
        this.topics = topics;
    }

    @EventListener
    void on(SnapshotValidationRequested event) {
        String type = event.type();
        if (!TOPIC.equals(type) && !LESSON.equals(type)) {
            return;
        }
        if (TOPIC.equals(type)) {
            validateTopic(event.itemId(), event.data());
        } else {
            validateLesson(event.data());
        }
        event.reply().complete(null);
    }

    private void validateTopic(UUID topicId, JsonNode data) {
        requireObject(data);
        onlyFields(data, "slug", "name", "description", "difficulty", "sort_order");
        String slug = requiredText(data, "slug", 120);
        if (!SLUG.matcher(slug).matches()) {
            fail("slug must use lowercase letters, digits and hyphens.");
        }
        if (topics.existsBySlugAndIdNot(slug, topicId)) {
            fail("slug is already used by another topic.");
        }
        plainText(data, "name", 200, true);
        plainText(data, "description", Integer.MAX_VALUE, false);
        if (data.hasNonNull("difficulty")
                && !DIFFICULTIES.contains(data.path("difficulty").asText())) {
            fail("difficulty must be BASIC, INTERMEDIATE or ADVANCED.");
        }
        optionalInt(data, "sort_order", 0, Integer.MAX_VALUE, false);
    }

    private void validateLesson(JsonNode data) {
        requireObject(data);
        onlyFields(
                data,
                "topic_id",
                "pinned_topic_revision_id",
                "title",
                "slug",
                "position",
                "body",
                "body_schema_version",
                "estimated_minutes");
        UUID topicId = requiredUuid(data, "topic_id");
        if (topics.findVisibleTopicById(topicId).isEmpty()) {
            fail("topic_id must reference a published topic.");
        }
        UUID pinned = requiredUuid(data, "pinned_topic_revision_id");
        if (content.snapshot(topicId, pinned, TOPIC, true).isEmpty()) {
            fail("pinned_topic_revision_id must be a published revision of that topic.");
        }
        plainText(data, "title", 200, true);
        String slug = requiredText(data, "slug", 120);
        if (!SLUG.matcher(slug).matches()) {
            fail("slug must use lowercase letters, digits and hyphens.");
        }
        optionalInt(data, "position", 0, MAX_POSITION, true);
        if (data.hasNonNull("body_schema_version")
                && (!data.path("body_schema_version").isIntegralNumber()
                        || data.path("body_schema_version").asInt() != 1)) {
            fail("body_schema_version must be 1.");
        }
        optionalInt(data, "estimated_minutes", 1, MAX_ESTIMATED_MINUTES, false);
        validateBody(data.path("body"));
    }

    private void validateBody(JsonNode body) {
        if (!body.isObject()) {
            fail("body must be an object.");
        }
        onlyFields(body, "blocks");
        JsonNode blocks = body.path("blocks");
        if (!blocks.isArray() || blocks.size() < 1 || blocks.size() > MAX_BLOCKS) {
            fail("body.blocks must contain 1 to " + MAX_BLOCKS + " blocks.");
        }
        if (body.toString().length() >= MAX_BODY_CHARS) {
            fail("body is too large.");
        }
        for (JsonNode block : blocks) {
            validateBlock(block);
        }
    }

    private void validateBlock(JsonNode block) {
        requireObject(block);
        String type = requiredText(block, "type", 16);
        if (!BLOCK_TYPES.contains(type)) {
            fail("Unsupported block type: " + type);
        }
        switch (type) {
            case "heading" -> {
                onlyFields(block, "type", "text");
                plainText(block, "text", 200, true);
            }
            case "paragraph", "note" -> {
                onlyFields(block, "type", "text");
                plainText(block, "text", 50_000, true);
            }
            case "example" -> {
                onlyFields(block, "type", "example_en", "example_vi");
                plainText(block, "example_en", 10_000, true);
                plainText(block, "example_vi", 10_000, false);
            }
            default -> {
                onlyFields(block, "type", "rows");
                validateRows(block.path("rows"));
            }
        }
    }

    private void validateRows(JsonNode rows) {
        if (!rows.isArray() || rows.size() < 1 || rows.size() > MAX_ROWS) {
            fail("table rows must contain 1 to " + MAX_ROWS + " rows.");
        }
        for (JsonNode row : rows) {
            if (!row.isArray() || row.size() < 1 || row.size() > MAX_CELLS) {
                fail("each table row must contain 1 to " + MAX_CELLS + " cells.");
            }
            for (JsonNode cell : row) {
                if (!cell.isTextual() || cell.asText().length() > 500) {
                    fail("table cells must be text of at most 500 characters.");
                }
                rejectHtml(cell.asText());
            }
        }
    }

    private static void requireObject(JsonNode node) {
        if (!node.isObject()) {
            fail("Expected a content object.");
        }
    }

    private static void onlyFields(JsonNode node, String... allowed) {
        Set<String> names = Set.of(allowed);
        node.fieldNames()
                .forEachRemaining(
                        name -> {
                            if (!names.contains(name)) {
                                fail("Unsupported snapshot field: " + name);
                            }
                        });
    }

    private static String requiredText(JsonNode node, String field, int max) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            fail(field + " is required.");
        }
        if (value.asText().length() > max) {
            fail(field + " must be at most " + max + " characters.");
        }
        return value.asText();
    }

    private static void plainText(JsonNode node, String field, int max, boolean required) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            if (required) {
                fail(field + " is required.");
            }
            return;
        }
        if (!value.isTextual()) {
            fail(field + " must be text.");
        }
        String text = value.asText();
        if (required && text.isBlank()) {
            fail(field + " is required.");
        }
        if (text.length() > max) {
            fail(field + " must be at most " + max + " characters.");
        }
        rejectHtml(text);
    }

    private static void rejectHtml(String text) {
        if (HTML.matcher(text).find()) {
            fail("HTML is not allowed in grammar content.");
        }
    }

    private static UUID requiredUuid(JsonNode node, String field) {
        String text = requiredText(node, field, 36);
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException ex) {
            throw invalid("Invalid " + field);
        }
    }

    private static void optionalInt(
            JsonNode node, String field, int min, int max, boolean required) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            if (required) {
                fail(field + " is required.");
            }
            return;
        }
        if (!value.isIntegralNumber()
                || !value.canConvertToInt()
                || value.asInt() < min
                || value.asInt() > max) {
            fail(field + " must be an integer from " + min + " to " + max + ".");
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message);
    }

    private static void fail(String message) {
        throw invalid(message);
    }
}
