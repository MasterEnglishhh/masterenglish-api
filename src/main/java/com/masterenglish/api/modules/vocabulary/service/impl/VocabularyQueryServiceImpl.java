package com.masterenglish.api.modules.vocabulary.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.common.api.WordSenseCardDto;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.common.events.ContentEvents;
import com.masterenglish.api.modules.vocabulary.dto.response.DeckDetailDto;
import com.masterenglish.api.modules.vocabulary.dto.response.DeckDto;
import com.masterenglish.api.modules.vocabulary.dto.response.WordDetailDto;
import com.masterenglish.api.modules.vocabulary.dto.response.WordListDto;
import com.masterenglish.api.modules.vocabulary.entity.Deck;
import com.masterenglish.api.modules.vocabulary.entity.Word;
import com.masterenglish.api.modules.vocabulary.entity.WordSense;
import com.masterenglish.api.modules.vocabulary.entity.WordSenseExample;
import com.masterenglish.api.modules.vocabulary.repository.DeckRepository;
import com.masterenglish.api.modules.vocabulary.repository.DeckWordRepository;
import com.masterenglish.api.modules.vocabulary.repository.WordRepository;
import com.masterenglish.api.modules.vocabulary.repository.WordSenseExampleRepository;
import com.masterenglish.api.modules.vocabulary.repository.WordSenseRepository;
import com.masterenglish.api.modules.vocabulary.service.VocabularyQueryService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class VocabularyQueryServiceImpl implements VocabularyQueryService {

    private final WordRepository wordRepository;
    private final WordSenseRepository senseRepository;
    private final WordSenseExampleRepository exampleRepository;
    private final DeckRepository deckRepository;
    private final DeckWordRepository deckWordRepository;
    private final ContentEvents content;
    private final ObjectMapper objectMapper;

    public VocabularyQueryServiceImpl(
            WordRepository wordRepository,
            WordSenseRepository senseRepository,
            WordSenseExampleRepository exampleRepository,
            DeckRepository deckRepository,
            DeckWordRepository deckWordRepository,
            ContentEvents content,
            ObjectMapper objectMapper) {
        this.wordRepository = wordRepository;
        this.senseRepository = senseRepository;
        this.exampleRepository = exampleRepository;
        this.deckRepository = deckRepository;
        this.deckWordRepository = deckWordRepository;
        this.content = content;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<WordListDto> getWords(
            String q, String cefrLevel, String partOfSpeech, Pageable pageable) {
        String cleanQ = q != null && !q.isBlank() ? q.trim() : null;
        String cleanCefr = cefrLevel != null && !cefrLevel.isBlank() ? cefrLevel.trim() : null;
        String cleanPos =
                partOfSpeech != null && !partOfSpeech.isBlank() ? partOfSpeech.trim() : null;

        Page<Word> page = wordRepository.searchWords(cleanQ, cleanCefr, cleanPos, pageable);

        return PageResponse.from(
                page,
                word -> {
                    List<WordSense> senses =
                            senseRepository.findByWordIdAndArchivedFalseOrderBySenseNoAsc(
                                    word.getId());
                    List<WordListDto.SenseSummaryDto> senseSummaries =
                            senses.stream()
                                    .filter(
                                            s ->
                                                    (cleanCefr == null
                                                                    || cleanCefr.equalsIgnoreCase(
                                                                            s.getCefrLevel()))
                                                            && (cleanPos == null
                                                                    || cleanPos.equalsIgnoreCase(
                                                                            s.getPartOfSpeech())))
                                    .map(
                                            s ->
                                                    new WordListDto.SenseSummaryDto(
                                                            s.getId(),
                                                            s.getSenseNo(),
                                                            s.getPartOfSpeech(),
                                                            s.getCefrLevel(),
                                                            s.getMeaningVi(),
                                                            s.getDefinitionEn()))
                                    .toList();

                    return new WordListDto(
                            word.getId(),
                            word.getLemma(),
                            word.getLanguageCode(),
                            word.getIpa(),
                            content.audioUrl(word.getId(), word.getPublishedRevisionId()),
                            senseSummaries);
                });
    }

    @Override
    @Transactional(readOnly = true)
    public WordDetailDto getWordDetail(UUID wordId) {
        log.debug("Reading vocabulary detail for word {}", wordId);
        Word word =
                wordRepository
                        .findActiveById(wordId)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.RESOURCE_NOT_FOUND,
                                                "Word not found: " + wordId));

        List<WordSense> senses =
                senseRepository.findByWordIdAndArchivedFalseOrderBySenseNoAsc(word.getId());
        List<WordDetailDto.SenseDetailDto> senseDetails =
                senses.stream()
                        .map(
                                s -> {
                                    List<WordSenseExample> examples =
                                            exampleRepository.findByWordSenseIdOrderByPositionAsc(
                                                    s.getId());
                                    List<WordDetailDto.ExampleDto> exampleDtos =
                                            examples.stream()
                                                    .map(
                                                            e ->
                                                                    new WordDetailDto.ExampleDto(
                                                                            e.getId(),
                                                                            e.getPosition(),
                                                                            e.getExampleEn(),
                                                                            e.getExampleVi()))
                                                    .toList();

                                    return new WordDetailDto.SenseDetailDto(
                                            s.getId(),
                                            s.getSenseNo(),
                                            s.getPartOfSpeech(),
                                            s.getCefrLevel(),
                                            s.getMeaningVi(),
                                            s.getDefinitionEn(),
                                            exampleDtos);
                                })
                        .toList();

        return new WordDetailDto(
                word.getId(),
                word.getLemma(),
                word.getLanguageCode(),
                word.getIpa(),
                content.audioUrl(word.getId(), word.getPublishedRevisionId()),
                senseDetails);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<DeckDto> getDecks(String q, String level, Pageable pageable) {
        String cleanQ = q != null && !q.isBlank() ? q.trim() : null;
        String cleanLevel = level != null && !level.isBlank() ? level.trim() : null;

        Page<Deck> page;
        if (cleanQ != null && cleanLevel != null) {
            page = deckRepository.searchByTitleAndLevel(cleanQ, cleanLevel, pageable);
        } else if (cleanQ != null) {
            page = deckRepository.searchByTitle(cleanQ, pageable);
        } else if (cleanLevel != null) {
            page = deckRepository.searchByLevel(cleanLevel, pageable);
        } else {
            page = deckRepository.findAllActive(pageable);
        }

        return PageResponse.from(
                page,
                deck -> {
                    long cardCount = deckWordRepository.countByIdDeckId(deck.getId());
                    return new DeckDto(
                            deck.getId(),
                            deck.getTitle(),
                            deck.getDescription(),
                            deck.getLevel(),
                            cardCount,
                            deck.getCreatedAt());
                });
    }

    @Override
    @Transactional(readOnly = true)
    public DeckDetailDto getDeckDetail(UUID deckId) {
        Deck deck =
                deckRepository
                        .findActiveById(deckId)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.RESOURCE_NOT_FOUND,
                                                "Deck not found: " + deckId));

        long cardCount = deckWordRepository.countByIdDeckId(deck.getId());
        return new DeckDetailDto(
                deck.getId(),
                deck.getTitle(),
                deck.getDescription(),
                deck.getLevel(),
                cardCount,
                deck.getPublishedRevisionId(),
                deck.getCreatedAt());
    }

    @Override
    @Transactional(readOnly = true)
    public List<WordSenseCardDto> getDeckCards(UUID deckId) {
        Deck deck =
                deckRepository
                        .findActiveById(deckId)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.RESOURCE_NOT_FOUND, "Deck not found."));
        return snapshotCards(deckId, deck.getPublishedRevisionId(), false);
    }

    private List<WordSenseCardDto> snapshotCards(UUID deckId, UUID revisionId, boolean lock) {
        String snapshot =
                content.snapshot(deckId, revisionId, "DECK", lock)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.RESOURCE_NOT_FOUND,
                                                "Deck revision unavailable."));
        var root = data(snapshot);
        List<WordSenseCardDto> cards = new ArrayList<>();
        for (var member : root.path("members")) {
            cardFromSnapshot(
                            deckId,
                            revisionId,
                            member.path("position").asInt(),
                            uuid(member, "word_sense_id"),
                            uuid(member, "word_id"),
                            uuid(member, "pinned_word_revision_id"),
                            lock)
                    .ifPresent(cards::add);
        }
        cards.sort(
                Comparator.comparingInt(WordSenseCardDto::position)
                        .thenComparing(WordSenseCardDto::wordSenseId));
        return cards;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WordSenseCardDto> getCardBySenseId(UUID senseId) {
        return senseRepository
                .findByIdAndArchivedFalse(senseId)
                .flatMap(
                        sense ->
                                wordRepository
                                        .findActiveById(sense.getWordId())
                                        .flatMap(
                                                word ->
                                                        cardFromSnapshot(
                                                                null,
                                                                null,
                                                                0,
                                                                senseId,
                                                                word.getId(),
                                                                word.getPublishedRevisionId(),
                                                                false)));
    }

    @Override
    @Transactional
    public WordSenseCardDto resolveReviewCard(
            UUID senseId, UUID wordId, UUID revisionId, UUID deckId, UUID deckRevisionId) {
        if ((deckId == null) != (deckRevisionId == null)) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Deck and deck revision must be provided together.");
        }
        Optional<WordSenseCardDto> card;
        if (deckId != null) {
            card =
                    snapshotCards(deckId, deckRevisionId, true).stream()
                            .filter(
                                    c ->
                                            c.wordSenseId().equals(senseId)
                                                    && c.wordId().equals(wordId)
                                                    && c.wordRevisionId().equals(revisionId))
                            .findFirst();
        } else {
            card = cardFromSnapshot(null, null, 0, senseId, wordId, revisionId, true);
        }
        return card.orElseThrow(
                () ->
                        new BusinessException(
                                ErrorCode.CONFLICT,
                                "Card context is no longer available. Reload the queue."));
    }

    private Optional<WordSenseCardDto> cardFromSnapshot(
            UUID deckId,
            UUID deckRevisionId,
            int position,
            UUID senseId,
            UUID wordId,
            UUID revisionId,
            boolean lock) {
        if (senseRepository
                .findByIdAndArchivedFalse(senseId)
                .filter(s -> s.getWordId().equals(wordId))
                .isEmpty()) return Optional.empty();
        return content.snapshot(wordId, revisionId, "WORD", lock)
                .flatMap(
                        snapshot -> {
                            var root = data(snapshot);
                            for (var sense : root.path("senses")) {
                                if (!sense.path("id").asText().equals(senseId.toString())) continue;
                                List<WordSenseCardDto.ExampleDto> examples = new ArrayList<>();
                                for (var example : sense.path("examples")) {
                                    examples.add(
                                            new WordSenseCardDto.ExampleDto(
                                                    uuid(example, "id"),
                                                    (short) example.path("position").asInt(),
                                                    example.path("example_en").asText(),
                                                    text(example, "example_vi")));
                                }
                                examples.sort(
                                        Comparator.comparingInt(
                                                WordSenseCardDto.ExampleDto::position));
                                return Optional.of(
                                        new WordSenseCardDto(
                                                deckId,
                                                deckRevisionId,
                                                position,
                                                senseId,
                                                wordId,
                                                revisionId,
                                                root.path("lemma").asText(),
                                                text(root, "ipa"),
                                                sense.path("part_of_speech").asText(),
                                                sense.path("cefr_level").asText(),
                                                sense.path("meaning_vi").asText(),
                                                text(sense, "definition_en"),
                                                examples,
                                                content.audioUrl(wordId, revisionId, senseId),
                                                false,
                                                null));
                            }
                            return Optional.empty();
                        });
    }

    private JsonNode data(String snapshot) {
        try {
            var root = objectMapper.readTree(snapshot);
            return root.has("data") ? root.path("data") : root;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "Published content is unreadable.");
        }
    }

    private UUID uuid(JsonNode node, String name) {
        return UUID.fromString(node.path(name).asText());
    }

    private String text(JsonNode node, String name) {
        return node.path(name).isMissingNode() || node.path(name).isNull()
                ? null
                : node.path(name).asText();
    }
}
