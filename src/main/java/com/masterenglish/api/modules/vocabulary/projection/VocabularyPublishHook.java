package com.masterenglish.api.modules.vocabulary.projection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.modules.content.authoring.ContentPublishHook;
import com.masterenglish.api.modules.vocabulary.entity.Deck;
import com.masterenglish.api.modules.vocabulary.entity.DeckWord;
import com.masterenglish.api.modules.vocabulary.entity.Word;
import com.masterenglish.api.modules.vocabulary.entity.WordSense;
import com.masterenglish.api.modules.vocabulary.entity.WordSenseExample;
import com.masterenglish.api.modules.vocabulary.repository.DeckRepository;
import com.masterenglish.api.modules.vocabulary.repository.DeckWordRepository;
import com.masterenglish.api.modules.vocabulary.repository.WordRepository;
import com.masterenglish.api.modules.vocabulary.repository.WordSenseExampleRepository;
import com.masterenglish.api.modules.vocabulary.repository.WordSenseRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional
public class VocabularyPublishHook implements ContentPublishHook {

    private final WordRepository wordRepository;
    private final WordSenseRepository senseRepository;
    private final WordSenseExampleRepository exampleRepository;
    private final DeckRepository deckRepository;
    private final DeckWordRepository deckWordRepository;
    private final ObjectMapper objectMapper;
    private final jakarta.persistence.EntityManager entityManager;

    public VocabularyPublishHook(
            WordRepository wordRepository,
            WordSenseRepository senseRepository,
            WordSenseExampleRepository exampleRepository,
            DeckRepository deckRepository,
            DeckWordRepository deckWordRepository,
            ObjectMapper objectMapper,
            jakarta.persistence.EntityManager entityManager) {
        this.wordRepository = wordRepository;
        this.senseRepository = senseRepository;
        this.exampleRepository = exampleRepository;
        this.deckRepository = deckRepository;
        this.deckWordRepository = deckWordRepository;
        this.objectMapper = objectMapper;
        this.entityManager = entityManager;
    }

    @Override
    public boolean supports(String contentType) {
        return "WORD".equals(contentType) || "DECK".equals(contentType);
    }

    @Override
    public void onPublish(UUID itemId, UUID revisionId, String contentType, String snapshotJson) {
        try {
            JsonNode root = objectMapper.readTree(snapshotJson);
            if (root.has("data")) root = root.path("data");
            if ("WORD".equals(contentType)) {
                projectWord(itemId, revisionId, root);
            } else if ("DECK".equals(contentType)) {
                projectDeck(itemId, revisionId, root);
            }
        } catch (Exception e) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Failed to project " + contentType + ": " + e.getMessage());
        }
    }

    private void projectWord(UUID wordId, UUID revisionId, JsonNode root) {
        String lemma = root.path("lemma").asText();
        String languageCode =
                root.has("language_code") ? root.get("language_code").asText("en") : "en";
        String ipa = root.has("ipa") && !root.get("ipa").isNull() ? root.get("ipa").asText() : null;
        UUID audioMediaId =
                root.has("audio_media_id") && !root.get("audio_media_id").isNull()
                        ? UUID.fromString(root.get("audio_media_id").asText())
                        : null;

        Word word =
                wordRepository
                        .findById(wordId)
                        .map(
                                existing -> {
                                    existing.update(
                                            revisionId, lemma, languageCode, ipa, audioMediaId);
                                    return existing;
                                })
                        .orElseGet(
                                () ->
                                        new Word(
                                                wordId,
                                                revisionId,
                                                lemma,
                                                languageCode,
                                                ipa,
                                                audioMediaId));
        wordRepository.save(word);

        JsonNode senses = root.get("senses");
        java.util.Set<UUID> retained = new java.util.HashSet<>();
        for (var node : senses) retained.add(UUID.fromString(node.path("id").asText()));
        for (var previous : senseRepository.findByWordIdAndArchivedFalseOrderBySenseNoAsc(wordId)) {
            if (!retained.contains(previous.getId())) previous.archive();
        }
        senseRepository.flush();
        if (senses != null && senses.isArray()) {
            for (JsonNode s : senses) {
                UUID senseId = UUID.fromString(s.path("id").asText());
                short senseNo = (short) s.path("sense_no").asInt(1);
                String pos = s.path("part_of_speech").asText();
                String cefr = s.path("cefr_level").asText();
                String meaningVi = s.path("meaning_vi").asText();
                String definitionEn =
                        s.has("definition_en") && !s.get("definition_en").isNull()
                                ? s.get("definition_en").asText()
                                : null;
                UUID senseAudioId =
                        s.has("audio_media_id") && !s.get("audio_media_id").isNull()
                                ? UUID.fromString(s.get("audio_media_id").asText())
                                : null;

                WordSense sense =
                        senseRepository
                                .findById(senseId)
                                .map(
                                        existing -> {
                                            existing.update(
                                                    revisionId,
                                                    senseNo,
                                                    pos,
                                                    cefr,
                                                    meaningVi,
                                                    definitionEn,
                                                    senseAudioId);
                                            return existing;
                                        })
                                .orElseGet(
                                        () ->
                                                new WordSense(
                                                        senseId,
                                                        wordId,
                                                        revisionId,
                                                        senseNo,
                                                        pos,
                                                        cefr,
                                                        meaningVi,
                                                        definitionEn,
                                                        senseAudioId));
                senseRepository.save(sense);

                for (var previous : exampleRepository.findByWordSenseIdOrderByPositionAsc(senseId))
                    entityManager.detach(previous);
                exampleRepository.deleteBySenseId(senseId);
                JsonNode examples = s.get("examples");
                if (examples != null && examples.isArray()) {
                    for (JsonNode ex : examples) {
                        UUID exId =
                                ex.has("id") && !ex.get("id").isNull()
                                        ? UUID.fromString(ex.get("id").asText())
                                        : UUID.randomUUID();
                        short position = (short) ex.path("position").asInt(1);
                        String exEn = ex.path("example_en").asText();
                        String exVi =
                                ex.has("example_vi") && !ex.get("example_vi").isNull()
                                        ? ex.get("example_vi").asText()
                                        : null;

                        if (!exEn.isBlank()) {
                            WordSenseExample example =
                                    new WordSenseExample(exId, senseId, position, exEn, exVi);
                            exampleRepository.save(example);
                        }
                    }
                }
            }
        }
    }

    private void projectDeck(UUID deckId, UUID revisionId, JsonNode root) {
        String title = root.path("title").asText();
        String description =
                root.has("description") && !root.get("description").isNull()
                        ? root.get("description").asText()
                        : null;
        String level =
                root.has("level") && !root.get("level").isNull()
                        ? root.get("level").asText()
                        : null;

        Deck deck =
                deckRepository
                        .findById(deckId)
                        .map(
                                existing -> {
                                    existing.update(revisionId, title, description, level);
                                    return existing;
                                })
                        .orElseGet(() -> new Deck(deckId, revisionId, title, description, level));
        deckRepository.save(deck);

        JsonNode members = root.get("members");
        if (members != null && members.isArray()) {
            deckWordRepository.deleteByIdDeckId(deckId);
            for (JsonNode m : members) {
                UUID wordSenseId = UUID.fromString(m.path("word_sense_id").asText());
                int position = m.path("position").asInt(1);
                DeckWord dw = new DeckWord(deckId, wordSenseId, position);
                deckWordRepository.save(dw);
            }
        }
    }
}
