package com.masterenglish.api.modules.learning.service;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.modules.vocabulary.query.WordSenseCardDto;
import java.util.UUID;
import org.springframework.data.domain.Pageable;

public interface LearningDeckCardService {

    PageResponse<WordSenseCardDto> getDeckCardsWithProgress(
            UUID userId, UUID deckId, String cefrLevel, String partOfSpeech, Pageable pageable);
}
