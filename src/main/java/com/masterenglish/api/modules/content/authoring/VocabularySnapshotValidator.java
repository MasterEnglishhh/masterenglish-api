package com.masterenglish.api.modules.content.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Typed validation for the text-only WORD/DECK slice before any projection is written. */
@Component
public class VocabularySnapshotValidator {
    private final PublishedContentReader reader;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private static final Set<String> LEVELS = Set.of("A1", "A2", "B1", "B2", "C1", "C2");
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

    public VocabularySnapshotValidator(
            PublishedContentReader reader,
            com.fasterxml.jackson.databind.ObjectMapper mapper,
            JdbcTemplate jdbc) {
        this.reader = reader;
        this.mapper = mapper;
        this.jdbc = jdbc;
    }

    public void validate(UUID itemId, String type, JsonNode root) {
        if (!root.isObject()) fail("Snapshot data must be an object.");
        if ("WORD".equals(type)) {
            fields(root, "lemma", "language_code", "ipa", "audio_media_id", "senses");
            required(root, "lemma");
            length(root, "lemma", 150);
            length(root, "language_code", 10);
            length(root, "ipa", 150);
            noMedia(root);
            array(root, "senses");
            Set<UUID> ids = new HashSet<>();
            Set<Integer> positions = new HashSet<>();
            Set<UUID> allExamples = new HashSet<>();
            for (JsonNode sense : root.path("senses")) {
                fields(
                        sense,
                        "id",
                        "sense_no",
                        "part_of_speech",
                        "cefr_level",
                        "meaning_vi",
                        "definition_en",
                        "audio_media_id",
                        "examples");
                UUID id = id(sense, "id");
                if (!ids.add(id) || !positions.add(positive(sense, "sense_no")))
                    fail("Duplicate sense or position.");
                if (!POS.contains(required(sense, "part_of_speech"))
                        || !LEVELS.contains(required(sense, "cefr_level")))
                    fail("Invalid sense filters.");
                required(sense, "meaning_vi");
                noMedia(sense);
                for (UUID owner :
                        jdbc.query(
                                "SELECT word_id FROM word_senses WHERE id=?",
                                (rs, n) -> rs.getObject(1, UUID.class),
                                id)) {
                    if (!owner.equals(itemId)) fail("A sense cannot move to another word.");
                }
                Set<Integer> examplePositions = new HashSet<>();
                if (sense.has("examples") && !sense.path("examples").isArray())
                    fail("Examples must be an array.");
                for (JsonNode example : sense.path("examples")) {
                    fields(example, "id", "position", "example_en", "example_vi");
                    if (!allExamples.add(id(example, "id"))
                            || !examplePositions.add(positive(example, "position")))
                        fail("Duplicate example.");
                    required(example, "example_en");
                    for (UUID owner :
                            jdbc.query(
                                    "SELECT word_sense_id FROM word_sense_examples WHERE id=?",
                                    (rs, n) -> rs.getObject(1, UUID.class),
                                    id(example, "id"))) {
                        if (!owner.equals(id)) fail("An example cannot move to another sense.");
                    }
                }
            }
        } else if ("DECK".equals(type)) {
            fields(root, "title", "description", "level", "members");
            required(root, "title");
            length(root, "title", 200);
            array(root, "members");
            if (root.hasNonNull("level") && !LEVELS.contains(root.path("level").asText()))
                fail("Invalid deck level.");
            Set<UUID> senses = new HashSet<>();
            Set<Integer> positions = new HashSet<>();
            for (JsonNode member : root.path("members")) {
                fields(member, "word_id", "word_sense_id", "pinned_word_revision_id", "position");
                UUID word = id(member, "word_id"),
                        sense = id(member, "word_sense_id"),
                        revision = id(member, "pinned_word_revision_id");
                if (!senses.add(sense) || !positions.add(positive(member, "position")))
                    fail("Duplicate deck member or position.");
                if (jdbc.queryForObject(
                                "SELECT count(*) FROM word_senses WHERE id=? AND word_id=? AND NOT is_archived",
                                Long.class,
                                sense,
                                word)
                        != 1L) fail("Deck member sense is not active.");
                String snapshot =
                        reader.snapshot(word, revision, "WORD", true)
                                .orElseThrow(
                                        () ->
                                                invalid(
                                                        "Deck child must be an accessible Published word."));
                try {
                    JsonNode child = mapper.readTree(snapshot);
                    if (child.has("data")) child = child.path("data");
                    boolean found = false;
                    for (JsonNode candidate : child.path("senses"))
                        if (candidate.path("id").asText().equals(sense.toString())) found = true;
                    if (!found) fail("Sense is not in the pinned word revision.");
                } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                    fail("Pinned word snapshot is invalid.");
                }
            }
        } else fail("This slice only supports WORD and DECK authoring.");
    }

    private void noMedia(JsonNode node) {
        if (node.hasNonNull("audio_media_id"))
            fail("Audio authoring is not enabled in this text-only slice.");
    }

    private void array(JsonNode node, String field) {
        if (!node.path(field).isArray() || node.path(field).isEmpty())
            fail(field + " must not be empty.");
    }

    private String required(JsonNode node, String field) {
        if (!node.path(field).isTextual() || node.path(field).asText().isBlank())
            fail(field + " is required.");
        return node.path(field).asText();
    }

    private int positive(JsonNode node, String field) {
        if (!node.path(field).isIntegralNumber()
                || !node.path(field).canConvertToInt()
                || node.path(field).asInt() < 1) fail(field + " must be positive.");
        if ((field.equals("sense_no") || node.has("example_en"))
                && node.path(field).asInt() > Short.MAX_VALUE)
            fail(field + " exceeds the supported position range.");
        return node.path(field).asInt();
    }

    private void fields(JsonNode node, String... allowed) {
        if (!node.isObject()) fail("Expected a content object.");
        Set<String> names = Set.of(allowed);
        node.fieldNames()
                .forEachRemaining(
                        name -> {
                            if (!names.contains(name)) fail("Unsupported snapshot field: " + name);
                            if (!Set.of("senses", "examples", "members", "position", "sense_no")
                                            .contains(name)
                                    && !node.path(name).isNull()
                                    && !node.path(name).isTextual()) {
                                fail("Expected text for " + name);
                            }
                        });
    }

    private void length(JsonNode node, String name, int max) {
        if (node.hasNonNull(name)
                && (!node.path(name).isTextual() || node.path(name).asText().length() > max))
            fail("Invalid " + name);
    }

    private UUID id(JsonNode node, String field) {
        try {
            return UUID.fromString(required(node, field));
        } catch (IllegalArgumentException ex) {
            throw invalid("Invalid " + field);
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message);
    }

    private static void fail(String message) {
        throw invalid(message);
    }
}
