package com.masterenglish.api.modules.learning.service.impl;

import com.masterenglish.api.common.api.WordSenseCardDto;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.common.events.VocabularyEvents;
import com.masterenglish.api.modules.learning.dto.response.DueQueueResponse;
import com.masterenglish.api.modules.learning.entity.UserWordProgress;
import com.masterenglish.api.modules.learning.repository.UserSavedWordRepository;
import com.masterenglish.api.modules.learning.repository.UserWordProgressRepository;
import com.masterenglish.api.modules.learning.service.DueQueueService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class DueQueueServiceImpl implements DueQueueService {

    private final UserWordProgressRepository progressRepository;
    private final UserSavedWordRepository savedWordRepository;
    private final VocabularyEvents vocabulary;
    private final Clock clock;

    public DueQueueServiceImpl(
            UserWordProgressRepository progressRepository,
            UserSavedWordRepository savedWordRepository,
            VocabularyEvents vocabulary,
            Clock clock) {
        this.progressRepository = progressRepository;
        this.savedWordRepository = savedWordRepository;
        this.vocabulary = vocabulary;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public DueQueueResponse getDueQueue(UUID userId, UUID deckId, int limit) {
        log.debug("Loading due review queue: deckId={}, limit={}", deckId, limit);
        if (limit < 1 || limit > 100) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "Limit must be between 1 and 100.");
        }
        int boundedLimit = limit;
        Instant serverNow = clock.instant();

        Map<UUID, WordSenseCardDto> deckCards =
                deckId == null
                        ? Map.of()
                        : vocabulary.getDeckCards(deckId).stream()
                                .collect(Collectors.toMap(WordSenseCardDto::wordSenseId, c -> c));
        long totalDue = progressRepository.countEligibleDue(userId, deckId, serverNow);
        List<UserWordProgress> dueItems =
                progressRepository.findEligibleDue(
                        userId, deckId, serverNow, PageRequest.of(0, boundedLimit));

        List<WordSenseCardDto> cards = new ArrayList<>();
        for (UserWordProgress p : dueItems) {
            Optional<WordSenseCardDto> cardOpt =
                    deckId == null
                            ? vocabulary.getCardBySenseId(p.getId().getWordSenseId())
                            : Optional.ofNullable(deckCards.get(p.getId().getWordSenseId()));
            if (cardOpt.isPresent()) {
                WordSenseCardDto card = cardOpt.get();
                boolean isSaved =
                        savedWordRepository.existsByIdUserIdAndIdWordSenseId(
                                userId, card.wordSenseId());
                WordSenseCardDto.ProgressSummaryDto progressDto =
                        new WordSenseCardDto.ProgressSummaryDto(
                                p.getRepetitions(),
                                p.getIntervalDays(),
                                p.getEaseFactor(),
                                p.getDueAt(),
                                p.getVersion());
                cards.add(card.withPersonalization(isSaved, progressDto));
            }
        }

        return new DueQueueResponse(totalDue, cards);
    }
}
