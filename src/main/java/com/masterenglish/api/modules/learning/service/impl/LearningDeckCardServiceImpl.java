package com.masterenglish.api.modules.learning.service.impl;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.common.api.WordSenseCardDto;
import com.masterenglish.api.common.events.VocabularyEvents;
import com.masterenglish.api.modules.learning.entity.UserWordProgress;
import com.masterenglish.api.modules.learning.repository.UserSavedWordRepository;
import com.masterenglish.api.modules.learning.repository.UserWordProgressRepository;
import com.masterenglish.api.modules.learning.service.LearningDeckCardService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class LearningDeckCardServiceImpl implements LearningDeckCardService {

    private final VocabularyEvents vocabulary;
    private final UserSavedWordRepository savedWordRepository;
    private final UserWordProgressRepository progressRepository;

    public LearningDeckCardServiceImpl(
            VocabularyEvents vocabulary,
            UserSavedWordRepository savedWordRepository,
            UserWordProgressRepository progressRepository) {
        this.vocabulary = vocabulary;
        this.savedWordRepository = savedWordRepository;
        this.progressRepository = progressRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<WordSenseCardDto> getDeckCardsWithProgress(
            UUID userId, UUID deckId, String cefrLevel, String partOfSpeech, Pageable pageable) {
        log.debug("Loading deck cards with progress: deckId={}", deckId);
        List<WordSenseCardDto> allCards = vocabulary.getDeckCards(deckId);

        // Filter by cefrLevel and partOfSpeech if requested
        List<WordSenseCardDto> filtered =
                allCards.stream()
                        .filter(
                                c ->
                                        (cefrLevel == null
                                                        || cefrLevel.equalsIgnoreCase(
                                                                c.cefrLevel()))
                                                && (partOfSpeech == null
                                                        || partOfSpeech.equalsIgnoreCase(
                                                                c.partOfSpeech())))
                        .toList();

        // Extract sense IDs for batch overlay
        List<UUID> senseIds = filtered.stream().map(WordSenseCardDto::wordSenseId).toList();

        Set<UUID> savedSenseIds =
                savedWordRepository.findByUserIdAndSenseIdIn(userId, senseIds).stream()
                        .map(sw -> sw.getId().getWordSenseId())
                        .collect(Collectors.toSet());

        Map<UUID, UserWordProgress> progressMap =
                progressRepository.findByUserIdAndSenseIdIn(userId, senseIds).stream()
                        .collect(Collectors.toMap(p -> p.getId().getWordSenseId(), p -> p));

        List<WordSenseCardDto> personalized =
                filtered.stream()
                        .map(
                                card -> {
                                    boolean isSaved = savedSenseIds.contains(card.wordSenseId());
                                    UserWordProgress prog = progressMap.get(card.wordSenseId());
                                    WordSenseCardDto.ProgressSummaryDto progressDto =
                                            prog != null
                                                    ? new WordSenseCardDto.ProgressSummaryDto(
                                                            prog.getRepetitions(),
                                                            prog.getIntervalDays(),
                                                            prog.getEaseFactor(),
                                                            prog.getDueAt(),
                                                            prog.getVersion())
                                                    : null;

                                    return card.withPersonalization(isSaved, progressDto);
                                })
                        .toList();

        // In-memory pagination for deck cards
        int start = (int) pageable.getOffset();
        int end = Math.min((start + pageable.getPageSize()), personalized.size());
        List<WordSenseCardDto> pageContent =
                start <= end ? personalized.subList(start, end) : List.of();

        Page<WordSenseCardDto> page = new PageImpl<>(pageContent, pageable, personalized.size());
        return PageResponse.from(page);
    }
}
