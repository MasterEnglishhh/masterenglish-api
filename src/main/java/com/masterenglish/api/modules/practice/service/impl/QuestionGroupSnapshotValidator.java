package com.masterenglish.api.modules.practice.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.common.events.ContentEvents.SnapshotValidationRequested;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class QuestionGroupSnapshotValidator {

    static final String QUESTION_GROUP = "QUESTION_GROUP";

    private static final Set<String> FIELDS =
            Set.of("category", "part_number", "group_type", "title", "materials", "children");
    private static final Set<String> MATERIAL_FIELDS =
            Set.of(
                    "id",
                    "position",
                    "document_key",
                    "material_type",
                    "usage_role",
                    "media_id",
                    "body");
    private static final Set<String> CHILD_FIELDS =
            Set.of("question_id", "revision_id", "position", "blank_key");
    private static final Pattern HTML = Pattern.compile("<\\s*/?\\s*[a-zA-Z!]");
    private static final Pattern BLANK_PATTERN = Pattern.compile("\\{\\{blank:([^}]+)\\}\\}");

    @EventListener
    void on(SnapshotValidationRequested event) {
        if (!QUESTION_GROUP.equals(event.type())) {
            return;
        }
        validate(event.itemId(), event.data());
        event.reply().complete(null);
    }

    public void validate(UUID groupId, JsonNode data) {
        if (data == null || !data.isObject()) {
            throw fail("Snapshot nhóm câu hỏi phải là một object JSON.");
        }
        rejectUnknown(data, FIELDS, "snapshot field");

        String category = text(data, "category");
        if (category == null || !Set.of("VOCABULARY", "GRAMMAR", "TOEIC").contains(category)) {
            throw fail("category phải là VOCABULARY, GRAMMAR hoặc TOEIC.");
        }

        Integer partNumber = null;
        if ("TOEIC".equals(category)) {
            if (!data.hasNonNull("part_number") || !data.path("part_number").isInt()) {
                throw fail("part_number là bắt buộc đối với nhóm câu hỏi TOEIC.");
            }
            partNumber = data.path("part_number").asInt();
            if (!Set.of(3, 4, 6, 7).contains(partNumber)) {
                throw fail("Nhóm câu hỏi TOEIC chỉ áp dụng cho Part 3, 4, 6 hoặc 7.");
            }
        } else {
            if (data.hasNonNull("part_number")) {
                throw fail("part_number phải là null đối với non-TOEIC.");
            }
        }

        String groupType = text(data, "group_type");
        if (groupType == null) {
            throw fail("group_type là bắt buộc.");
        }
        if ("TOEIC".equals(category)) {
            if (partNumber == 3 && !"CONVERSATION".equals(groupType)) {
                throw fail("TOEIC Part 3 group_type phải là CONVERSATION.");
            } else if (partNumber == 4 && !"TALK".equals(groupType)) {
                throw fail("TOEIC Part 4 group_type phải là TALK.");
            } else if (partNumber == 6 && !"TEXT_COMPLETION".equals(groupType)) {
                throw fail("TOEIC Part 6 group_type phải là TEXT_COMPLETION.");
            } else if (partNumber == 7 && !"PASSAGE".equals(groupType)) {
                throw fail("TOEIC Part 7 group_type phải là PASSAGE.");
            }
        } else {
            if (!"GENERAL".equals(groupType)) {
                throw fail("Non-TOEIC group_type phải là GENERAL.");
            }
        }

        plainText(data, "title", 200);

        String stimulusBody = validateMaterials(data.path("materials"), category, partNumber);
        validateChildren(data.path("children"), category, partNumber, stimulusBody);
    }

    private String validateMaterials(JsonNode materials, String category, Integer partNumber) {
        if (!materials.isArray() || materials.isEmpty() || materials.size() > 10) {
            throw fail("materials phải là mảng từ 1 đến 10 phần tử.");
        }
        Set<Integer> positions = new HashSet<>();
        Set<String> documentKeys = new HashSet<>();
        Set<String> seenDocumentKeys = new HashSet<>();
        boolean hasStimulusAudio = false;
        String stimulusText = null;

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
            if (!Set.of("TEXT", "IMAGE", "AUDIO").contains(type)) {
                throw fail("material_type chỉ nhận TEXT, IMAGE hoặc AUDIO.");
            }
            String role = mat.hasNonNull("usage_role") ? text(mat, "usage_role") : "STIMULUS";
            if (!Set.of("STIMULUS", "TRANSCRIPT", "EXPLANATION").contains(role)) {
                throw fail("usage_role chỉ nhận STIMULUS, TRANSCRIPT hoặc EXPLANATION.");
            }
            if ("AUDIO".equals(type) && "STIMULUS".equals(role)) {
                hasStimulusAudio = true;
            }
            if ("TEXT".equals(type)) {
                if (mat.hasNonNull("media_id")) {
                    throw fail("material TEXT không được chứa media_id.");
                }
                plainText(mat, "body", 50000);
                if ("STIMULUS".equals(role)) {
                    stimulusText = text(mat, "body");
                }
            } else {
                if (mat.hasNonNull("body")) {
                    throw fail("material IMAGE/AUDIO không được chứa body.");
                }
                uuid(mat.path("media_id"), "material.media_id phải là UUID.");
            }
            if (mat.hasNonNull("document_key")) {
                String docKey = text(mat, "document_key");
                if (docKey == null || docKey.isBlank() || docKey.length() > 80) {
                    throw fail("document_key không được trống hoặc vượt quá 80 ký tự.");
                }
                documentKeys.add(docKey);
            }
        }

        if ("TOEIC".equals(category)) {
            if ((partNumber == 3 || partNumber == 4) && !hasStimulusAudio) {
                throw fail(
                        "TOEIC Part " + partNumber + " yêu cầu học liệu AUDIO vai trò STIMULUS.");
            }
            if (partNumber == 6 && stimulusText == null) {
                throw fail("TOEIC Part 6 yêu cầu học liệu TEXT vai trò STIMULUS.");
            }
            if (partNumber == 7 && (documentKeys.isEmpty() || documentKeys.size() > 3)) {
                throw fail("TOEIC Part 7 yêu cầu từ 1 đến 3 document_key phân biệt.");
            }
        }
        return stimulusText;
    }

    private void validateChildren(
            JsonNode children, String category, Integer partNumber, String stimulusBody) {
        if (!children.isArray()) {
            throw fail("children phải là mảng.");
        }
        int count = children.size();
        if ("TOEIC".equals(category)) {
            if ((partNumber == 3 || partNumber == 4) && count != 3) {
                throw fail("TOEIC Part " + partNumber + " yêu cầu đúng 3 câu hỏi.");
            }
            if (partNumber == 6 && count != 4) {
                throw fail("TOEIC Part 6 yêu cầu đúng 4 câu hỏi.");
            }
            if (partNumber == 7 && (count < 2 || count > 5)) {
                throw fail("TOEIC Part 7 yêu cầu từ 2 đến 5 câu hỏi.");
            }
        } else {
            if (count < 1 || count > 10) {
                throw fail("Nhóm câu hỏi yêu cầu từ 1 đến 10 câu con.");
            }
        }

        Set<UUID> questionIds = new HashSet<>();
        Set<Integer> positions = new HashSet<>();
        Set<String> blankKeys = new HashSet<>();

        for (JsonNode child : children) {
            if (!child.isObject()) {
                throw fail("Mỗi child question phải là object.");
            }
            rejectUnknown(child, CHILD_FIELDS, "child field");
            UUID qId = uuid(child.path("question_id"), "child.question_id phải là UUID.");
            if (!questionIds.add(qId)) {
                throw fail("child.question_id không được trùng nhau: " + qId);
            }
            if (child.hasNonNull("revision_id")) {
                uuid(child.path("revision_id"), "child.revision_id phải là UUID.");
            }
            JsonNode posNode = child.path("position");
            if (!posNode.isInt() || posNode.asInt() < 1 || !positions.add(posNode.asInt())) {
                throw fail("child.position phải là số nguyên dương duy nhất.");
            }
            if ("TOEIC".equals(category) && partNumber == 6) {
                requireText(child, "blank_key", "blank_key là bắt buộc trong TOEIC Part 6.");
                String bKey = text(child, "blank_key");
                if (!blankKeys.add(bKey)) {
                    throw fail("blank_key bị trùng: " + bKey);
                }
            } else {
                if (child.hasNonNull("blank_key")) {
                    throw fail("blank_key chỉ áp dụng cho TOEIC Part 6.");
                }
            }
        }

        if ("TOEIC".equals(category) && partNumber == 6 && stimulusBody != null) {
            Set<String> markersInText = new HashSet<>();
            Matcher matcher = BLANK_PATTERN.matcher(stimulusBody);
            while (matcher.find()) {
                String marker = matcher.group(1).trim();
                if (!markersInText.add(marker)) {
                    throw fail("Marker {{blank:" + marker + "}} xuất hiện nhiều hơn 1 lần.");
                }
            }
            if (!markersInText.equals(blankKeys)) {
                throw fail(
                        "Tập vị trí trống {{blank:<key>}} phải khớp chính xác với blank_key của các câu hỏi con.");
            }
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
