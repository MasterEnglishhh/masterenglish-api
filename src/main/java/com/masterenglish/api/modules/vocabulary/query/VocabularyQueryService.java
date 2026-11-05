package com.masterenglish.api.modules.vocabulary.query;

import com.masterenglish.api.common.api.PageResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;

public interface VocabularyQueryService {

    PageResponse<WordListDto> getWords(
            String q, String cefrLevel, String partOfSpeech, Pageable pageable);

    WordDetailDto getWordDetail(UUID wordId);

    PageResponse<DeckDto> getDecks(String q, String level, Pageable pageable);

    DeckDetailDto getDeckDetail(UUID deckId);

    List<WordSenseCardDto> getDeckCards(UUID deckId);

    Optional<WordSenseCardDto> getCardBySenseId(UUID wordSenseId);

    WordSenseCardDto resolveReviewCard(
            UUID senseId, UUID wordId, UUID revisionId, UUID deckId, UUID deckRevisionId);
}
