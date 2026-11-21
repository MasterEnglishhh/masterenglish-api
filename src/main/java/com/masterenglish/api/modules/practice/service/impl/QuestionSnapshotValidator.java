package com.masterenglish.api.modules.practice.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.common.events.ContentEvents.SnapshotValidationRequested;
import com.masterenglish.api.common.events.GrammarEvents;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Validates QUESTION snapshots before publication. It answers only QUESTION requests; other content
 * types are left to their own validators.
 */
@Component
public class QuestionSnapshotValidator {

    static final String QUESTION = "QUESTION";

    private static final Set<String> FIELDS =
            Set.of(
                    "category",
                    "question_type",
                    "practice_mode",
                    "difficulty",
                    "prompt",
                    "explanation",
                    "topic_ids",
                    "options");
    private static final Set<String> OPTION_FIELDS = Set.of("body", "is_correct");
    private static final Set<String> PRACTICE_MODES = Set.of("STANDARD", "FILL_OPTION");
    private static final Set<String> DIFFICULTIES = Set.of("EASY", "MEDIUM", "HARD");
    private static final Pattern HTML = Pattern.compile("<\\s*/?\\s*[a-zA-Z!]");
    private static final int MAX_PROMPT = 10_000;
    private static final int MAX_EXPLANATION = 20_000;
    private static final int MAX_OPTION_BODY = 2_000;
    private static final int MIN_OPTIONS = 2;
    private static final int MAX_OPTIONS = 6;
    private static final int MAX_TOPICS = 10;

    private final GrammarEvents grammar;

    public QuestionSnapshotValidator(GrammarEvents grammar) {
        this.grammar = grammar;
    }

    @EventListener
    void on(SnapshotValidationRequested event) {
        if (!QUESTION.equals(event.type())) {
            return;
        }
        validate(event.data());
        event.reply().complete(null);
    }

    private void validate(JsonNode data) {
        if (data == null || !data.isObject()) {
            throw fail("Snapshot câu hỏi phải là một object JSON.");
        }
        for (Iterator<String> names = data.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!FIELDS.contains(name)) {
                throw fail("Unsupported snapshot field: " + name);
            }
        }
        if (!"GRAMMAR".equals(text(data, "category"))) {
            throw fail("category phải là GRAMMAR.");
        }
        if (data.hasNonNull("question_type")
                && !"SINGLE_CHOICE".equals(text(data, "question_type"))) {
            throw fail("question_type chỉ nhận SINGLE_CHOICE.");
        }
        String mode = data.hasNonNull("practice_mode") ? text(data, "practice_mode") : "STANDARD";
        if (!PRACTICE_MODES.contains(mode)) {
            throw fail("practice_mode không hợp lệ.");
        }
        String difficulty = data.hasNonNull("difficulty") ? text(data, "difficulty") : "MEDIUM";
        if (!DIFFICULTIES.contains(difficulty)) {
            throw fail("difficulty chỉ nhận EASY, MEDIUM hoặc HARD.");
        }
        plainText(data, "prompt", MAX_PROMPT);
        plainText(data, "explanation", MAX_EXPLANATION);
        validateTopics(data.path("topic_ids"));
        validateOptions(data.path("options"));
    }

    private void validateTopics(JsonNode topics) {
        if (!topics.isArray() || topics.size() < 1 || topics.size() > MAX_TOPICS) {
            throw fail("topic_ids phải là mảng từ 1 đến " + MAX_TOPICS + " phần tử.");
        }
        Set<UUID> seen = new HashSet<>();
        for (JsonNode topic : topics) {
            UUID topicId = uuid(topic);
            if (!seen.add(topicId)) {
                throw fail("topic_ids không được trùng nhau.");
            }
            if (!grammar.isVisibleTopic(topicId)) {
                throw fail("topic_ids phải tham chiếu chủ điểm đã xuất bản.");
            }
        }
    }

    private void validateOptions(JsonNode options) {
        if (!options.isArray() || options.size() < MIN_OPTIONS || options.size() > MAX_OPTIONS) {
            throw fail("options phải là mảng từ 2 đến 6 phần tử.");
        }
        int correct = 0;
        for (JsonNode option : options) {
            if (!option.isObject()) {
                throw fail("Mỗi lựa chọn phải là object.");
            }
            for (Iterator<String> names = option.fieldNames(); names.hasNext(); ) {
                String name = names.next();
                if (!OPTION_FIELDS.contains(name)) {
                    throw fail("Unsupported option field: " + name);
                }
            }
            plainText(option, "body", MAX_OPTION_BODY);
            if (!option.path("is_correct").isBoolean()) {
                throw fail("is_correct phải là true hoặc false.");
            }
            if (option.path("is_correct").asBoolean()) {
                correct++;
            }
        }
        if (correct != 1) {
            throw fail("Mỗi câu hỏi phải có đúng một đáp án đúng.");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    private static void plainText(JsonNode node, String field, int max) {
        String value = text(node, field);
        if (value == null || value.isBlank()) {
            throw fail(field + " là bắt buộc.");
        }
        if (value.length() > max) {
            throw fail(field + " vượt quá " + max + " ký tự.");
        }
        if (HTML.matcher(value).find()) {
            throw fail(field + " không được chứa thẻ HTML.");
        }
    }

    private static UUID uuid(JsonNode node) {
        if (!node.isTextual()) {
            throw fail("topic_ids phải là chuỗi UUID.");
        }
        try {
            return UUID.fromString(node.asText());
        } catch (IllegalArgumentException ex) {
            throw fail("topic_ids chứa UUID không hợp lệ.");
        }
    }

    private static BusinessException fail(String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message);
    }
}
