package com.masterenglish.api.modules.content.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class QuestionBankJsonParser {

    private static final Pattern HTML_PATTERN = Pattern.compile("<\\s*/?\\s*[a-zA-Z!]");
    private static final Pattern BLANK_PATTERN = Pattern.compile("\\{\\{blank:([^}]+)\\}\\}");

    private static final Set<String> ROOT_ALLOWED_FIELDS =
            Set.of("schema_version", "items", "metadata", "namespace");
    private static final Set<String> ROW_COMMON_FIELDS =
            Set.of(
                    "type",
                    "external_key",
                    "source_id",
                    "category",
                    "part_number",
                    "question_type_code",
                    "practice_mode",
                    "difficulty",
                    "prompt",
                    "options",
                    "answer_key",
                    "explanation",
                    "materials",
                    "topic_ids",
                    "word_sense_ids",
                    "group_type",
                    "title",
                    "children");

    private final ObjectMapper mapper;

    public QuestionBankJsonParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public record ParsedRow(
            int rowNumber,
            String inputHash,
            String externalKey,
            String payloadJson,
            String status,
            String errorsJson,
            UUID sourceId,
            String type) {}

    public record ParseResult(
            List<ParsedRow> rows, int totalRows, int validRows, int invalidRows) {}

    public ParseResult parse(byte[] jsonBytes, Set<UUID> existingSourceIds) {
        if (jsonBytes == null || jsonBytes.length == 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "JSON file is empty");
        }

        // Strict UTF-8 validation
        try {
            CharsetDecoder decoder =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT);
            decoder.decode(ByteBuffer.wrap(jsonBytes));
        } catch (CharacterCodingException e) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "File contains invalid UTF-8 byte sequence.");
        }

        JsonNode root;
        try {
            root = mapper.readTree(jsonBytes);
        } catch (Exception e) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "Malformed JSON content: " + e.getMessage());
        }

        if (!root.isObject()) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "JSON root must be an object with schema_version and items.");
        }

        if (jsonDepth(root) > 10) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "JSON nesting depth exceeds maximum limit of 10.");
        }

        for (Iterator<String> it = root.fieldNames(); it.hasNext(); ) {
            String field = it.next();
            if (!ROOT_ALLOWED_FIELDS.contains(field)) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "Unsupported root field: " + field);
            }
        }

        if (!root.hasNonNull("schema_version") || root.path("schema_version").asInt() != 1) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "schema_version must be 1.");
        }

        if (!root.hasNonNull("items") || !root.path("items").isArray()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "items array is required.");
        }

        JsonNode items = root.path("items");
        if (items.size() > 5000) {
            throw new BusinessException(
                    ErrorCode.PAYLOAD_TOO_LARGE, "Item count exceeds 5,000 logical items limit.");
        }

        List<ParsedRow> parsedRows = new ArrayList<>(items.size());
        Set<String> seenExternalKeys = new HashSet<>();
        int validCount = 0;
        int invalidCount = 0;

        for (int i = 0; i < items.size(); i++) {
            int rowNumber = i + 1;
            JsonNode itemNode = items.get(i);
            List<String> errors = new ArrayList<>();

            if (!itemNode.isObject()) {
                parsedRows.add(
                        new ParsedRow(
                                rowNumber,
                                sha256Hex("invalid_row_" + rowNumber),
                                "row_" + rowNumber,
                                "{}",
                                "INVALID",
                                serializeErrors(List.of("Mỗi item phải là một JSON object.")),
                                null,
                                "UNKNOWN"));
                invalidCount++;
                continue;
            }

            String externalKey = text(itemNode, "external_key");
            if (externalKey == null || externalKey.isBlank()) {
                errors.add("external_key là bắt buộc.");
                externalKey = "row_" + rowNumber;
            } else if (externalKey.length() > 80) {
                errors.add("external_key không được vượt quá 80 ký tự.");
            } else if (!seenExternalKeys.add(externalKey)) {
                errors.add("external_key bị trùng lặp: " + externalKey);
            }

            UUID sourceId = null;
            if (itemNode.hasNonNull("source_id")) {
                try {
                    sourceId = UUID.fromString(itemNode.path("source_id").asText().strip());
                    if (!existingSourceIds.contains(sourceId)) {
                        errors.add("source_id không tồn tại hoặc không ACTIVE: " + sourceId);
                    }
                } catch (Exception ex) {
                    errors.add("source_id không phải là UUID hợp lệ.");
                }
            }

            String type = text(itemNode, "type");
            if (type == null) {
                if (itemNode.has("children") || itemNode.has("group_type")) {
                    type = "QUESTION_GROUP";
                } else {
                    type = "QUESTION";
                }
            } else if (!Set.of("QUESTION", "QUESTION_GROUP").contains(type)) {
                errors.add("type chỉ nhận QUESTION hoặc QUESTION_GROUP.");
            }

            if ("QUESTION".equals(type)) {
                validateQuestionItem(itemNode, false, errors);
            } else if ("QUESTION_GROUP".equals(type)) {
                validateQuestionGroupItem(itemNode, errors, existingSourceIds);
            }

            String inputHash = sha256Hex(itemNode.toString());
            String status = errors.isEmpty() ? "VALID" : "INVALID";
            String errorsJson = errors.isEmpty() ? null : serializeErrors(errors);

            if (errors.isEmpty()) {
                validCount++;
            } else {
                invalidCount++;
            }

            parsedRows.add(
                    new ParsedRow(
                            rowNumber,
                            inputHash,
                            externalKey,
                            itemNode.toString(),
                            status,
                            errorsJson,
                            sourceId,
                            type));
        }

        return new ParseResult(parsedRows, items.size(), validCount, invalidCount);
    }

    private void validateQuestionItem(JsonNode qNode, boolean isChildOfGroup, List<String> errors) {
        String category = text(qNode, "category");
        if (category == null || !Set.of("VOCABULARY", "GRAMMAR", "TOEIC").contains(category)) {
            errors.add("category phải là VOCABULARY, GRAMMAR hoặc TOEIC.");
        }

        Integer part = qNode.hasNonNull("part_number") ? qNode.path("part_number").asInt() : null;
        if ("TOEIC".equals(category)) {
            if (!isChildOfGroup) {
                if (part == null || (part != 1 && part != 2 && part != 5)) {
                    errors.add("Câu hỏi độc lập TOEIC chỉ áp dụng cho Part 1, 2 hoặc 5.");
                }
            } else {
                if (part != null && (part < 1 || part > 7)) {
                    errors.add("part_number TOEIC không hợp lệ: " + part);
                }
            }
        } else {
            if (part != null) {
                errors.add("part_number phải là null đối với non-TOEIC.");
            }
        }

        String prompt = text(qNode, "prompt");
        if (prompt == null || prompt.isBlank()) {
            errors.add("prompt là bắt buộc.");
        } else if (prompt.length() > 10000) {
            errors.add("prompt vượt quá 10000 ký tự.");
        } else if (HTML_PATTERN.matcher(prompt).find()) {
            errors.add("prompt không được chứa thẻ HTML.");
        }

        String explanation = text(qNode, "explanation");
        if (explanation != null && explanation.length() > 20000) {
            errors.add("explanation vượt quá 20000 ký tự.");
        } else if (explanation != null && HTML_PATTERN.matcher(explanation).find()) {
            errors.add("explanation không được chứa thẻ HTML.");
        }

        if (qNode.hasNonNull("options")) {
            JsonNode options = qNode.path("options");
            if (!options.isArray() || options.size() < 2 || options.size() > 6) {
                errors.add("options phải là mảng từ 2 đến 6 phần tử.");
            } else {
                Set<Integer> posSet = new HashSet<>();
                Set<String> idSet = new HashSet<>();
                for (JsonNode opt : options) {
                    if (!opt.isObject()) {
                        errors.add("Mỗi option phải là object.");
                        continue;
                    }
                    if (opt.hasNonNull("id")) {
                        String id = opt.path("id").asText().strip();
                        try {
                            UUID.fromString(id);
                        } catch (Exception ex) {
                            errors.add("options.id phải là UUID hợp lệ: " + id);
                        }
                        if (!idSet.add(id)) {
                            errors.add("options.id không được trùng: " + id);
                        }
                    } else {
                        errors.add("options.id là bắt buộc.");
                    }
                    int pos = opt.path("position").asInt();
                    if (pos < 1 || pos > 6 || !posSet.add(pos)) {
                        errors.add("options.position phải là số nguyên duy nhất từ 1 đến 6.");
                    }
                    String body = text(opt, "body");
                    if (body == null || body.isBlank()) {
                        errors.add("options.body là bắt buộc.");
                    } else if (HTML_PATTERN.matcher(body).find()) {
                        errors.add("options.body không được chứa thẻ HTML.");
                    }
                }
            }
        } else {
            errors.add("options là bắt buộc.");
        }

        if (!qNode.hasNonNull("answer_key")) {
            errors.add("answer_key là bắt buộc.");
        }
    }

    private void validateQuestionGroupItem(
            JsonNode gNode, List<String> errors, Set<UUID> existingSourceIds) {
        String category = text(gNode, "category");
        if (category == null || !Set.of("VOCABULARY", "GRAMMAR", "TOEIC").contains(category)) {
            errors.add("category phải là VOCABULARY, GRAMMAR hoặc TOEIC.");
        }

        Integer part = gNode.hasNonNull("part_number") ? gNode.path("part_number").asInt() : null;
        if ("TOEIC".equals(category)) {
            if (part == null || (part != 3 && part != 4 && part != 6 && part != 7)) {
                errors.add("Nhóm câu hỏi TOEIC chỉ áp dụng cho Part 3, 4, 6 hoặc 7.");
            }
        } else {
            if (part != null) {
                errors.add("part_number phải là null đối với non-TOEIC.");
            }
        }

        String groupType = text(gNode, "group_type");
        if (groupType == null) {
            errors.add("group_type là bắt buộc.");
        }

        String title = text(gNode, "title");
        if (title == null || title.isBlank()) {
            errors.add("title là bắt buộc.");
        } else if (title.length() > 200) {
            errors.add("title vượt quá 200 ký tự.");
        } else if (HTML_PATTERN.matcher(title).find()) {
            errors.add("title không được chứa thẻ HTML.");
        }

        String stimulusBody = null;
        if (gNode.hasNonNull("materials")) {
            JsonNode mats = gNode.path("materials");
            if (!mats.isArray() || mats.isEmpty() || mats.size() > 10) {
                errors.add("materials phải là mảng từ 1 đến 10 phần tử.");
            } else {
                for (JsonNode m : mats) {
                    String matType = text(m, "material_type");
                    String role = text(m, "usage_role");
                    if ("TEXT".equals(matType) && "STIMULUS".equals(role)) {
                        stimulusBody = text(m, "body");
                    }
                }
            }
        } else {
            errors.add("materials là bắt buộc.");
        }

        if (gNode.hasNonNull("children")) {
            JsonNode children = gNode.path("children");
            if (!children.isArray() || children.isEmpty()) {
                errors.add("children phải là mảng câu hỏi con không rỗng.");
            } else {
                int count = children.size();
                if ("TOEIC".equals(category) && part != null) {
                    if ((part == 3 || part == 4) && count != 3) {
                        errors.add("TOEIC Part " + part + " yêu cầu đúng 3 câu hỏi.");
                    } else if (part == 6 && count != 4) {
                        errors.add("TOEIC Part 6 yêu cầu đúng 4 câu hỏi.");
                    } else if (part == 7 && (count < 2 || count > 5)) {
                        errors.add("TOEIC Part 7 yêu cầu từ 2 đến 5 câu hỏi.");
                    }
                }

                Set<String> blankKeys = new HashSet<>();
                for (int ci = 0; ci < children.size(); ci++) {
                    JsonNode child = children.get(ci);
                    if (child.hasNonNull("blank_key")) {
                        blankKeys.add(child.path("blank_key").asText().strip());
                    }
                    if (child.hasNonNull("prompt")) {
                        // Inline child validation
                        validateQuestionItem(child, true, errors);
                    }
                }

                if ("TOEIC".equals(category) && part != null && part == 6 && stimulusBody != null) {
                    Set<String> markers = new HashSet<>();
                    Matcher matcher = BLANK_PATTERN.matcher(stimulusBody);
                    while (matcher.find()) {
                        markers.add(matcher.group(1).trim());
                    }
                    if (!markers.equals(blankKeys)) {
                        errors.add(
                                "Tập vị trí trống {{blank:<key>}} phải khớp chính xác với blank_key của các câu hỏi con.");
                    }
                }
            }
        } else {
            errors.add("children là bắt buộc.");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode val = node.path(field);
        return val.isTextual() ? val.asText().strip() : null;
    }

    private String serializeErrors(List<String> errors) {
        ArrayNode arr = mapper.createArrayNode();
        for (String err : errors) {
            arr.addObject().put("field", "item").put("message", err);
        }
        return arr.toString();
    }

    private static int jsonDepth(JsonNode node) {
        if (node == null || !node.isContainerNode()) {
            return 0;
        }
        int max = 0;
        for (JsonNode child : node) {
            max = Math.max(max, jsonDepth(child));
        }
        return 1 + max;
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
