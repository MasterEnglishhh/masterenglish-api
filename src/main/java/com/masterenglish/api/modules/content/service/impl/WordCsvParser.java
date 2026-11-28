package com.masterenglish.api.modules.content.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class WordCsvParser {

    private static final Set<String> POS =
            Set.of(
                    "NOUN",
                    "VERB",
                    "ADJECTIVE",
                    "ADVERB",
                    "PRONOUN",
                    "PREPOSITION",
                    "CONJUNCTION",
                    "DETERMINER",
                    "INTERJECTION",
                    "NUMERAL",
                    "PHRASE");

    private static final Set<String> LEVELS = Set.of("A1", "A2", "B1", "B2", "C1", "C2");

    private final ObjectMapper mapper;

    public WordCsvParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public record ParsedRow(
            int rowNumber,
            String inputHash,
            String externalKey,
            String payloadJson,
            String status,
            String errorsJson,
            UUID sourceId) {}

    public record ParseResult(
            List<ParsedRow> rows, int totalRows, int validRows, int invalidRows) {}

    public ParseResult parse(byte[] csvBytes, Set<UUID> existingSourceIds) {
        if (csvBytes == null || csvBytes.length == 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "CSV file is empty");
        }

        CharsetDecoder decoder =
                StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT);
        String text;
        try {
            CharBuffer decoded = decoder.decode(ByteBuffer.wrap(csvBytes));
            text = decoded.toString();
        } catch (CharacterCodingException e) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "CSV file is not valid UTF-8: " + e.getMessage());
        }
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }

        List<List<String>> records = parseRfc4180(text);
        if (records.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "CSV file contains no records");
        }

        List<String> headers =
                records.get(0).stream().map(String::trim).map(String::toLowerCase).toList();
        Map<String, Integer> colIndex = new HashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            colIndex.put(headers.get(i), i);
        }

        // Validate required headers
        for (String req : List.of("lemma", "part_of_speech", "cefr_level", "meaning_vi")) {
            if (!colIndex.containsKey(req)) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "Missing required CSV column: " + req);
            }
        }

        int dataRowCount = records.size() - 1;
        if (dataRowCount <= 0) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "CSV file contains no data rows");
        }
        if (dataRowCount > 5000) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "CSV exceeds maximum of 5,000 data rows");
        }

        List<ParsedRow> parsedRows = new ArrayList<>(dataRowCount);
        int validCount = 0;
        int invalidCount = 0;

        for (int i = 1; i < records.size(); i++) {
            List<String> record = records.get(i);
            int rowNumber = i;

            String externalKey = getCol(record, colIndex, "external_key");
            String lemma = getCol(record, colIndex, "lemma");
            String ipa = getCol(record, colIndex, "ipa");
            String pos = getCol(record, colIndex, "part_of_speech");
            if (pos != null) pos = pos.toUpperCase();
            String cefr = getCol(record, colIndex, "cefr_level");
            if (cefr != null) cefr = cefr.toUpperCase();
            String meaningVi = getCol(record, colIndex, "meaning_vi");
            String definitionEn = getCol(record, colIndex, "definition_en");
            String exampleEn = getCol(record, colIndex, "example_en");
            String exampleVi = getCol(record, colIndex, "example_vi");
            String sourceIdStr = getCol(record, colIndex, "source_id");

            ArrayNode errors = mapper.createArrayNode();
            if (lemma == null || lemma.isBlank()) {
                addError(errors, "lemma", "Lemma is required.");
            } else if (lemma.length() > 150) {
                addError(errors, "lemma", "Lemma must not exceed 150 characters.");
            }

            if (pos == null || !POS.contains(pos)) {
                addError(errors, "partOfSpeech", "Invalid part of speech.");
            }

            if (cefr == null || !LEVELS.contains(cefr)) {
                addError(errors, "cefrLevel", "Invalid CEFR level.");
            }

            if (meaningVi == null || meaningVi.isBlank()) {
                addError(errors, "meaningVi", "Meaning (VI) is required.");
            }

            if (ipa != null && ipa.length() > 150) {
                addError(errors, "ipa", "IPA must not exceed 150 characters.");
            }

            UUID sourceId = null;
            if (sourceIdStr != null && !sourceIdStr.isBlank()) {
                try {
                    sourceId = UUID.fromString(sourceIdStr);
                    if (existingSourceIds != null && !existingSourceIds.contains(sourceId)) {
                        addError(errors, "sourceId", "Source ID does not exist: " + sourceIdStr);
                    }
                } catch (IllegalArgumentException e) {
                    addError(errors, "sourceId", "Invalid UUID format for sourceId.");
                }
            }

            // Build JSON payload
            ObjectNode payload = mapper.createObjectNode();
            payload.put("lemma", lemma != null ? lemma : "");
            payload.put("language_code", "en");
            if (ipa != null) payload.put("ipa", ipa);

            ArrayNode senses = payload.putArray("senses");
            ObjectNode sense = senses.addObject();
            UUID senseId = UUID.randomUUID();
            sense.put("id", senseId.toString());
            sense.put("sense_no", 1);
            sense.put("part_of_speech", pos != null ? pos : "");
            sense.put("cefr_level", cefr != null ? cefr : "");
            sense.put("meaning_vi", meaningVi != null ? meaningVi : "");
            if (definitionEn != null && !definitionEn.isBlank()) {
                sense.put("definition_en", definitionEn);
            }

            if (exampleEn != null && !exampleEn.isBlank()) {
                ArrayNode examples = sense.putArray("examples");
                ObjectNode ex = examples.addObject();
                ex.put("id", UUID.randomUUID().toString());
                ex.put("position", 1);
                ex.put("example_en", exampleEn);
                if (exampleVi != null && !exampleVi.isBlank()) {
                    ex.put("example_vi", exampleVi);
                }
            }

            if (sourceId != null) {
                payload.put("source_id", sourceId.toString());
            }

            String payloadJson = payload.toString();
            String inputHash = sha256Hex(String.join(",", record));
            boolean isValid = errors.isEmpty();
            String status = isValid ? "VALID" : "INVALID";
            if (isValid) {
                validCount++;
            } else {
                invalidCount++;
            }

            parsedRows.add(
                    new ParsedRow(
                            rowNumber,
                            inputHash,
                            externalKey,
                            payloadJson,
                            status,
                            errors.toString(),
                            sourceId));
        }

        return new ParseResult(parsedRows, dataRowCount, validCount, invalidCount);
    }

    private static String getCol(List<String> record, Map<String, Integer> colIndex, String col) {
        Integer idx = colIndex.get(col);
        if (idx == null || idx >= record.size()) return null;
        String val = record.get(idx).trim();
        return val.isEmpty() ? null : val;
    }

    private static void addError(ArrayNode errors, String field, String message) {
        errors.addObject().put("field", field).put("message", message);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** RFC 4180 compliant CSV parser. */
    public static List<List<String>> parseRfc4180(String text) {
        List<List<String>> records = new ArrayList<>();
        if (text == null || text.isEmpty()) return records;

        List<String> currentRecord = new ArrayList<>();
        StringBuilder currentField = new StringBuilder();
        boolean inQuotes = false;
        int len = text.length();

        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);

            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < len && text.charAt(i + 1) == '"') {
                        currentField.append('"');
                        i++; // skip escaped quote
                    } else {
                        inQuotes = false;
                    }
                } else {
                    currentField.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                } else if (c == ',') {
                    currentRecord.add(currentField.toString());
                    currentField.setLength(0);
                } else if (c == '\r') {
                    if (i + 1 < len && text.charAt(i + 1) == '\n') {
                        i++; // skip \n
                    }
                    currentRecord.add(currentField.toString());
                    currentField.setLength(0);
                    records.add(currentRecord);
                    currentRecord = new ArrayList<>();
                } else if (c == '\n') {
                    currentRecord.add(currentField.toString());
                    currentField.setLength(0);
                    records.add(currentRecord);
                    currentRecord = new ArrayList<>();
                } else {
                    currentField.append(c);
                }
            }
        }

        if (currentField.length() > 0 || !currentRecord.isEmpty()) {
            currentRecord.add(currentField.toString());
            records.add(currentRecord);
        }

        return records;
    }
}
