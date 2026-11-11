package com.masterenglish.api.modules.learning.service;

import com.masterenglish.api.modules.learning.dto.DueQueueResponse;
import com.masterenglish.api.modules.learning.entity.UserWordProgress;
import com.masterenglish.api.modules.learning.repository.UserSavedWordRepository;
import com.masterenglish.api.modules.learning.repository.UserWordProgressRepository;
import com.masterenglish.api.modules.vocabulary.query.VocabularyQueryService;
import com.masterenglish.api.modules.vocabulary.query.WordSenseCardDto;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class DueQueueServiceImpl implements DueQueueService {

    private final UserWordProgressRepository progressRepository;
    private final UserSavedWordRepository savedWordRepository;
    private final VocabularyQueryService vocabularyQueryService;
    private final Clock clock;

    public DueQueueServiceImpl(
            UserWordProgressRepository progressRepository,
            UserSavedWordRepository savedWordRepository,
            VocabularyQueryService vocabularyQueryService,
            Clock clock) {
        this.progressRepository = progressRepository;
        this.savedWordRepository = savedWordRepository;
        this.vocabularyQueryService = vocabularyQueryService;
        this.clock = clock;
    }

    @Override
    public DueQueueResponse getDueQueue(UUID userId, UUID deckId, int limit) {
        if (limit < 1 || limit > 100) {
            throw new com.masterenglish.api.common.error.BusinessException(
                    com.masterenglish.api.common.error.ErrorCode.VALIDATION_ERROR,
                    "Limit must be between 1 and 100.");
        }
        int boundedLimit = limit;
        Instant serverNow = clock.instant();

        java.util.Map<UUID, WordSenseCardDto> deckCards =
                deckId == null
                        ? java.util.Map.of()
                        : vocabularyQueryService.getDeckCards(deckId).stream()
                                .collect(
                                        java.util.stream.Collectors.toMap(
                                                WordSenseCardDto::wordSenseId, c -> c));
        long totalDue = progressRepository.countEligibleDue(userId, deckId, serverNow);
        List<UserWordProgress> dueItems =
                progressRepository.findEligibleDue(
                        userId, deckId, serverNow, PageRequest.of(0, boundedLimit));

        List<WordSenseCardDto> cards = new ArrayList<>();
        for (UserWordProgress p : dueItems) {
            Optional<WordSenseCardDto> cardOpt =
                    deckId == null
                            ? vocabularyQueryService.getCardBySenseId(p.getId().getWordSenseId())
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
