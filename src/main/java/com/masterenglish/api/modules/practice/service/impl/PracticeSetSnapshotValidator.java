package com.masterenglish.api.modules.practice.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.common.events.ContentEvents.SnapshotValidationRequested;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class PracticeSetSnapshotValidator {

    static final String PRACTICE_SET = "PRACTICE_SET";

    private static final Set<String> FIELDS =
            Set.of(
                    "category",
                    "title",
                    "selection_mode",
                    "selection_config",
                    "entries",
                    "deck_id",
                    "topic_id",
                    "part_number",
                    "time_limit_seconds");
    private static final Set<String> ENTRY_FIELDS =
            Set.of("position", "question_id", "group_id", "pinned_revision_id");
    private static final Set<String> DYNAMIC_FIELDS =
            Set.of(
                    "version",
                    "deck_id",
                    "mode",
                    "topic_ids",
                    "part_number",
                    "difficulty",
                    "question_count");
    private static final Set<String> VOCABULARY_MODES =
            Set.of("FLASHCARD", "MULTIPLE_CHOICE", "MIXED");
    private static final Set<String> DIFFICULTIES = Set.of("EASY", "MEDIUM", "HARD");
    private static final Pattern HTML = Pattern.compile("<\\s*/?\\s*[a-zA-Z!]");

    @EventListener
    void on(SnapshotValidationRequested event) {
        if (!PRACTICE_SET.equals(event.type())) {
            return;
        }
        validate(event.itemId(), event.data());
        event.reply().complete(null);
    }

    public void validate(UUID setId, JsonNode data) {
        if (data == null || !data.isObject()) {
            throw fail("Snapshot bộ luyện phải là một object JSON.");
        }
        rejectUnknown(data, FIELDS, "snapshot field");

        String category = text(data, "category");
        if (category == null || !Set.of("VOCABULARY", "GRAMMAR", "TOEIC").contains(category)) {
            throw fail("category phải là VOCABULARY, GRAMMAR hoặc TOEIC.");
        }

        plainText(data, "title", 200);

        String selectionMode = text(data, "selection_mode");
        if (selectionMode == null || !Set.of("FIXED", "DYNAMIC").contains(selectionMode)) {
            throw fail("selection_mode chỉ nhận FIXED hoặc DYNAMIC.");
        }

        if (data.hasNonNull("time_limit_seconds")) {
            if (!data.path("time_limit_seconds").isInt()
                    || data.path("time_limit_seconds").asInt() <= 0) {
                throw fail("time_limit_seconds phải là số nguyên dương.");
            }
        }

        if ("TOEIC".equals(category)) {
            if (data.hasNonNull("part_number")) {
                JsonNode partNode = data.path("part_number");
                int part = partNode.asInt();
                if (!partNode.isInt() || part < 1 || part > 7) {
                    throw fail("part_number phải từ 1 đến 7.");
                }
            }
        } else {
            if (data.hasNonNull("part_number")) {
                throw fail("part_number phải là null đối với non-TOEIC.");
            }
        }

        if (data.hasNonNull("deck_id")) {
            if (!"VOCABULARY".equals(category)) {
                throw fail("deck_id chỉ áp dụng cho category VOCABULARY.");
            }
            uuid(data.path("deck_id"), "deck_id phải là UUID.");
        }

        if (data.hasNonNull("topic_id")) {
            if (!"GRAMMAR".equals(category)) {
                throw fail("topic_id chỉ áp dụng cho category GRAMMAR.");
            }
            uuid(data.path("topic_id"), "topic_id phải là UUID.");
        }

        if ("FIXED".equals(selectionMode)) {
            validateFixedEntries(data.path("entries"));
            if (data.hasNonNull("selection_config") && !data.path("selection_config").isEmpty()) {
                throw fail("selection_mode FIXED không nhận selection_config.");
            }
        } else {
            if (data.hasNonNull("entries") && !data.path("entries").isEmpty()) {
                throw fail("selection_mode DYNAMIC không nhận entries.");
            }
            if (!data.hasNonNull("selection_config") || !data.path("selection_config").isObject()) {
                throw fail(
                        "selection_config phải là một object JSON đối với selection_mode DYNAMIC.");
            }
            JsonNode selConfig = data.path("selection_config");
            rejectUnknown(selConfig, DYNAMIC_FIELDS, "selection_config field");
            int supportedVersion = 1;
            if (!selConfig.hasNonNull("version")
                    || !selConfig.path("version").isInt()
                    || selConfig.path("version").asInt() != supportedVersion) {
                throw fail("selection_config version không được hỗ trợ cho category.");
            }
            validateDynamicFilters(category, data, selConfig);
        }
    }

    private void validateDynamicFilters(String category, JsonNode setData, JsonNode config) {
        JsonNode setPartNumber = setData.path("part_number");
        if (!config.path("version").isInt()) {
            throw fail("selection_config.version phải là số nguyên.");
        }
        if (!config.hasNonNull("question_count")
                || !config.path("question_count").isInt()
                || config.path("question_count").asInt() < 1
                || config.path("question_count").asInt() > 100) {
            throw fail("selection_config.question_count phải từ 1 đến 100.");
        }
        if (config.hasNonNull("difficulty")
                && (!config.path("difficulty").isTextual()
                        || !DIFFICULTIES.contains(config.path("difficulty").asText()))) {
            throw fail("selection_config.difficulty không hợp lệ.");
        }
        switch (category) {
            case "VOCABULARY" -> {
                if (config.hasNonNull("topic_ids") || config.hasNonNull("part_number")) {
                    throw fail("Vocabulary DYNAMIC không nhận topic/Part.");
                }
                if (config.hasNonNull("deck_id")) {
                    uuid(config.path("deck_id"), "selection_config.deck_id phải là UUID.");
                }
                if (config.hasNonNull("mode")
                        && (!config.path("mode").isTextual()
                                || !VOCABULARY_MODES.contains(config.path("mode").asText()))) {
                    throw fail("selection_config.mode không hợp lệ.");
                }
            }
            case "GRAMMAR" -> {
                if (config.hasNonNull("deck_id") || config.hasNonNull("part_number")) {
                    throw fail("Grammar DYNAMIC không nhận deck/Part.");
                }
                if (config.hasNonNull("mode")) {
                    throw fail("Grammar DYNAMIC không nhận mode.");
                }
                if (config.hasNonNull("topic_ids")) {
                    validateUuidArray(config.path("topic_ids"), "selection_config.topic_ids");
                }
            }
            case "TOEIC" -> {
                if (!setPartNumber.isInt()
                        || config.hasNonNull("deck_id")
                        || config.hasNonNull("topic_ids")) {
                    throw fail("TOEIC DYNAMIC yêu cầu Part trên set, không nhận deck/topics.");
                }
                if (config.hasNonNull("mode")) {
                    throw fail("TOEIC DYNAMIC không nhận mode.");
                }
                if (config.hasNonNull("part_number")
                        && (!config.path("part_number").isInt()
                                || config.path("part_number").asInt() != setPartNumber.asInt())) {
                    throw fail("selection_config.part_number phải khớp part_number của set.");
                }
            }
            default -> throw fail("category không hỗ trợ cho DYNAMIC Practice Set.");
        }
    }

    private static void validateUuidArray(JsonNode values, String field) {
        if (!values.isArray() || values.isEmpty()) {
            throw fail(field + " phải là mảng UUID không rỗng.");
        }
        Set<UUID> ids = new HashSet<>();
        for (JsonNode value : values) {
            UUID id = uuid(value, field + " phải chứa UUID hợp lệ.");
            if (!ids.add(id)) {
                throw fail(field + " không được trùng UUID.");
            }
        }
    }

    private void validateFixedEntries(JsonNode entries) {
        if (!entries.isArray() || entries.isEmpty()) {
            throw fail("entries phải là mảng không rỗng.");
        }
        Set<Integer> positions = new HashSet<>();
        int totalQuestions = 0;
        for (JsonNode entry : entries) {
            if (!entry.isObject()) {
                throw fail("Mỗi entry phải là object.");
            }
            rejectUnknown(entry, ENTRY_FIELDS, "entry field");
            JsonNode posNode = entry.path("position");
            if (!posNode.isInt() || posNode.asInt() < 1 || !positions.add(posNode.asInt())) {
                throw fail("entry.position phải là số nguyên dương duy nhất.");
            }
            boolean hasQuestion = entry.hasNonNull("question_id");
            boolean hasGroup = entry.hasNonNull("group_id");
            if (hasQuestion == hasGroup) {
                throw fail("Mỗi entry phải chứa đúng một trong question_id hoặc group_id (XOR).");
            }
            if (hasQuestion) {
                uuid(entry.path("question_id"), "entry.question_id phải là UUID.");
            }
            if (hasGroup) {
                uuid(entry.path("group_id"), "entry.group_id phải là UUID.");
            }
            uuid(entry.path("pinned_revision_id"), "entry.pinned_revision_id phải là UUID.");
            totalQuestions++;
        }
        if (totalQuestions < 1 || totalQuestions > 100) {
            throw fail("Tổng số câu hỏi trong entries phải từ 1 đến 100.");
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

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText().trim() : null;
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
            return UUID.fromString(node.asText().trim());
        } catch (IllegalArgumentException ex) {
            throw fail(message);
        }
    }

    private static BusinessException fail(String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message);
    }
}
