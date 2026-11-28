package com.masterenglish.api.modules.practice.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.common.events.ContentEvents.SnapshotValidationRequested;
import com.masterenglish.api.common.events.GrammarEvents;
import com.masterenglish.api.modules.practice.repository.QuestionOptionRepository;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Validates the data of a QUESTION revision (SCHEMA section 4) before publication. It answers only
 * QUESTION requests; other content types are left to their own validators.
 */
@Component
public class QuestionSnapshotValidator {

    static final String QUESTION = "QUESTION";

    private static final Set<String> FIELDS =
            Set.of(
                    "category",
                    "part_number",
                    "question_type_code",
                    "practice_mode",
                    "difficulty",
                    "prompt",
                    "group",
                    "options",
                    "answer_key",
                    "explanation",
                    "materials",
                    "topic_ids",
                    "word_sense_ids");
    private static final Set<String> OPTION_FIELDS = Set.of("id", "position", "body");
    private static final Set<String> ANSWER_KEY_FIELDS = Set.of("correctOptionId");
    private static final Set<String> MATERIAL_FIELDS =
            Set.of(
                    "id",
                    "position",
                    "material_type",
                    "usage_role",
                    "media_id",
                    "body",
                    "document_key");
    private static final Set<String> MATERIAL_TYPES = Set.of("TEXT", "IMAGE", "AUDIO");
    private static final Set<String> USAGE_ROLES = Set.of("STIMULUS", "TRANSCRIPT", "EXPLANATION");
    private static final Set<String> PRACTICE_MODES = Set.of("STANDARD", "FILL_OPTION");
    private static final Set<String> DIFFICULTIES = Set.of("EASY", "MEDIUM", "HARD");
    private static final Pattern HTML = Pattern.compile("<\\s*/?\\s*[a-zA-Z!]");
    private static final int MAX_PROMPT = 10_000;
    private static final int MAX_EXPLANATION = 20_000;
    private static final int MAX_OPTION_BODY = 2_000;
    private static final int MAX_MATERIAL_BODY = 50_000;
    private static final int MIN_OPTIONS = 2;
    private static final int MAX_OPTIONS = 6;
    private static final int MAX_TOPICS = 10;

    private final GrammarEvents grammar;
    private final QuestionOptionRepository questionOptions;

    public QuestionSnapshotValidator(
            GrammarEvents grammar, QuestionOptionRepository questionOptions) {
        this.grammar = grammar;
        this.questionOptions = questionOptions;
    }

    @EventListener
    void on(SnapshotValidationRequested event) {
        if (!QUESTION.equals(event.type())) {
            return;
        }
        validate(event.itemId(), event.data());
        event.reply().complete(null);
    }

    private void validate(UUID questionId, JsonNode data) {
        if (data == null || !data.isObject()) {
            throw fail("Snapshot câu hỏi phải là một object JSON.");
        }
        rejectUnknown(data, FIELDS, "snapshot field");
        String category = text(data, "category");
        if (!Set.of("GRAMMAR", "VOCABULARY", "TOEIC").contains(category)) {
            throw fail("category phải là GRAMMAR, VOCABULARY hoặc TOEIC.");
        }
        Integer partNumber = null;
        if ("TOEIC".equals(category)) {
            if (!data.hasNonNull("part_number") || !data.path("part_number").isInt()) {
                throw fail("part_number là bắt buộc đối với câu hỏi TOEIC.");
            }
            partNumber = data.path("part_number").asInt();
            if (partNumber < 1 || partNumber > 7) {
                throw fail("part_number phải từ 1 đến 7.");
            }
        } else if ("GRAMMAR".equals(category)) {
            requireAbsentOrNull(
                    data, "part_number", "part_number phải là null trong Practice M04.");
            requireAbsentOrNull(data, "group", "group phải là null trong Practice M04.");
            requireAbsentOrEmptyArray(data, "materials", "materials phải là mảng rỗng trong M04.");
            requireAbsentOrEmptyArray(
                    data, "word_sense_ids", "word_sense_ids phải là mảng rỗng trong M04.");
        } else {
            requireAbsentOrNull(data, "part_number", "part_number phải là null đối với non-TOEIC.");
        }

        // Required at publication (drafts may omit them). No silent defaults: a missing value is an
        // error.
        requireText(data, "question_type_code", "question_type_code là bắt buộc khi publish.");
        if (!"SINGLE_CHOICE".equals(text(data, "question_type_code"))) {
            throw fail("question_type_code chỉ nhận SINGLE_CHOICE.");
        }
        requireText(data, "practice_mode", "practice_mode là bắt buộc khi publish.");
        if (!PRACTICE_MODES.contains(text(data, "practice_mode"))) {
            throw fail("practice_mode không hợp lệ.");
        }
        requireText(data, "difficulty", "difficulty là bắt buộc khi publish.");
        if (!DIFFICULTIES.contains(text(data, "difficulty"))) {
            throw fail("difficulty chỉ nhận EASY, MEDIUM hoặc HARD.");
        }
        plainText(data, "prompt", MAX_PROMPT);
        plainText(data, "explanation", MAX_EXPLANATION);

        if ("GRAMMAR".equals(category)) {
            validateTopics(data.path("topic_ids"));
            requireAbsentOrEmptyArray(
                    data, "word_sense_ids", "word_sense_ids phải là mảng rỗng trong GRAMMAR.");
        } else if ("VOCABULARY".equals(category)) {
            validateWordSenses(data.path("word_sense_ids"));
        } else if ("TOEIC".equals(category)) {
            if (data.hasNonNull("topic_ids")
                    && data.path("topic_ids").isArray()
                    && !data.path("topic_ids").isEmpty()) {
                validateTopics(data.path("topic_ids"));
            }
            if (data.hasNonNull("word_sense_ids")
                    && data.path("word_sense_ids").isArray()
                    && !data.path("word_sense_ids").isEmpty()) {
                validateWordSenses(data.path("word_sense_ids"));
            }
        }

        if (data.hasNonNull("materials")) {
            validateMaterials(data.path("materials"));
        }

        Set<UUID> optionIds =
                validateOptions(questionId, data.path("options"), category, partNumber);
        validateAnswerKey(data.path("answer_key"), optionIds);
    }

    private void validateWordSenses(JsonNode senses) {
        if (!senses.isArray() || senses.size() < 1 || senses.size() > 10) {
            throw fail("word_sense_ids phải là mảng từ 1 đến 10 phần tử.");
        }
        Set<UUID> seen = new HashSet<>();
        for (JsonNode s : senses) {
            UUID senseId = uuid(s, "word_sense_ids chứa UUID không hợp lệ.");
            if (!seen.add(senseId)) {
                throw fail("word_sense_ids không được trùng nhau.");
            }
        }
    }

    private void validateMaterials(JsonNode materials) {
        if (!materials.isArray() || materials.size() > 10) {
            throw fail("materials phải là mảng tối đa 10 phần tử.");
        }
        Set<Integer> positions = new HashSet<>();
        for (JsonNode mat : materials) {
            if (!mat.isObject()) {
                throw fail("Mỗi material phải là object.");
            }
            rejectUnknown(mat, MATERIAL_FIELDS, "material field");
            if (mat.hasNonNull("id")) {
                uuid(mat.path("id"), "material.id phải là UUID.");
            }
            JsonNode posNode = mat.path("position");
            if (!posNode.isInt() || posNode.asInt() < 1 || !positions.add(posNode.asInt())) {
                throw fail("material.position phải là số nguyên dương duy nhất.");
            }
            requireText(mat, "material_type", "material_type là bắt buộc.");
            String type = text(mat, "material_type");
            if (!MATERIAL_TYPES.contains(type)) {
                throw fail("material_type chỉ nhận TEXT, IMAGE hoặc AUDIO.");
            }
            if (mat.hasNonNull("usage_role")) {
                String role = text(mat, "usage_role");
                if (!USAGE_ROLES.contains(role)) {
                    throw fail("usage_role chỉ nhận STIMULUS, TRANSCRIPT hoặc EXPLANATION.");
                }
            }
            if ("TEXT".equals(type)) {
                if (mat.hasNonNull("media_id")) {
                    throw fail("material TEXT không được chứa media_id.");
                }
                plainText(mat, "body", MAX_MATERIAL_BODY);
            } else {
                if (mat.hasNonNull("body")) {
                    throw fail("material IMAGE/AUDIO không được chứa body.");
                }
                uuid(mat.path("media_id"), "material.media_id phải là UUID.");
            }
            if (mat.hasNonNull("document_key")) {
                String docKey = text(mat, "document_key");
                if (docKey == null || docKey.length() > 80) {
                    throw fail("document_key không được vượt quá 80 ký tự.");
                }
            }
        }
    }

    private void validateTopics(JsonNode topics) {
        if (!topics.isArray() || topics.size() < 1 || topics.size() > MAX_TOPICS) {
            throw fail("topic_ids phải là mảng từ 1 đến " + MAX_TOPICS + " phần tử.");
        }
        Set<UUID> seen = new HashSet<>();
        for (JsonNode topic : topics) {
            UUID topicId = uuid(topic, "topic_ids chứa UUID không hợp lệ.");
            if (!seen.add(topicId)) {
                throw fail("topic_ids không được trùng nhau.");
            }
            if (!grammar.isVisibleTopic(topicId)) {
                throw fail("topic_ids phải tham chiếu chủ điểm đã xuất bản.");
            }
        }
    }

    /**
     * Option ids are stable across revisions, so each must be unique and must not belong to another
     * question.
     */
    private Set<UUID> validateOptions(
            UUID questionId, JsonNode options, String category, Integer partNumber) {
        int minOptions =
                "TOEIC".equals(category)
                        ? (partNumber != null && partNumber == 2 ? 3 : 4)
                        : MIN_OPTIONS;
        int maxOptions =
                "TOEIC".equals(category)
                        ? (partNumber != null && partNumber == 2 ? 3 : 4)
                        : MAX_OPTIONS;
        if (!options.isArray() || options.size() < minOptions || options.size() > maxOptions) {
            if ("TOEIC".equals(category)) {
                throw fail(
                        "TOEIC Part " + partNumber + " yêu cầu đúng " + minOptions + " lựa chọn.");
            }
            throw fail(
                    "options phải là mảng từ " + minOptions + " đến " + maxOptions + " phần tử.");
        }
        Set<UUID> ids = new HashSet<>();
        Set<Integer> positions = new HashSet<>();
        for (JsonNode option : options) {
            if (!option.isObject()) {
                throw fail("Mỗi lựa chọn phải là object.");
            }
            rejectUnknown(option, OPTION_FIELDS, "option field");
            UUID id = uuid(option.path("id"), "options.id phải là UUID.");
            if (!ids.add(id)) {
                throw fail("options.id không được trùng nhau.");
            }
            if (questionOptions.existsByIdAndQuestionIdNot(id, questionId)) {
                throw fail("options.id đã thuộc câu hỏi khác.");
            }
            JsonNode position = option.path("position");
            if (!position.isInt()
                    || position.asInt() < 1
                    || position.asInt() > maxOptions
                    || !positions.add(position.asInt())) {
                throw fail("position phải là số nguyên duy nhất từ 1 đến " + maxOptions + ".");
            }
            plainText(option, "body", MAX_OPTION_BODY);
        }
        return ids;
    }

    private void validateAnswerKey(JsonNode answerKey, Set<UUID> optionIds) {
        if (!answerKey.isObject()) {
            throw fail("answer_key phải là object.");
        }
        rejectUnknown(answerKey, ANSWER_KEY_FIELDS, "answer_key field");
        UUID correct =
                uuid(answerKey.path("correctOptionId"), "answer_key.correctOptionId phải là UUID.");
        if (!optionIds.contains(correct)) {
            throw fail("answer_key.correctOptionId phải là một lựa chọn của câu hỏi.");
        }
    }

    private static void rejectUnknown(JsonNode node, Set<String> allowed, String label) {
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!allowed.contains(name)) {
                throw fail("Unsupported " + label + ": " + name);
            }
        }
    }

    private static void requireText(JsonNode node, String field, String message) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw fail(message);
        }
    }

    private static void requireAbsentOrNull(JsonNode node, String field, String message) {
        if (node.has(field) && !node.get(field).isNull()) {
            throw fail(message);
        }
    }

    private static void requireAbsentOrEmptyArray(JsonNode node, String field, String message) {
        if (node.has(field) && (!node.get(field).isArray() || node.get(field).size() > 0)) {
            throw fail(message);
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

    private static UUID uuid(JsonNode node, String message) {
        if (!node.isTextual()) {
            throw fail(message);
        }
        try {
            return UUID.fromString(node.asText());
        } catch (IllegalArgumentException ex) {
            throw fail(message);
        }
    }

    private static BusinessException fail(String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message);
    }
}
