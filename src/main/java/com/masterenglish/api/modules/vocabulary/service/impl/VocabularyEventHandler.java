package com.masterenglish.api.modules.vocabulary.service.impl;

import com.masterenglish.api.common.events.VocabularyEvents.ActiveDecksByIdsRequested;
import com.masterenglish.api.common.events.VocabularyEvents.DeckCardsRequested;
import com.masterenglish.api.common.events.VocabularyEvents.ReviewCardValidationRequested;
import com.masterenglish.api.common.events.VocabularyEvents.SenseCardRequested;
import com.masterenglish.api.common.events.VocabularyEvents.WordAccessRequested;
import com.masterenglish.api.modules.vocabulary.service.VocabularyQueryService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
class VocabularyEventHandler {
    private final VocabularyQueryService vocabulary;

    VocabularyEventHandler(VocabularyQueryService vocabulary) {
        this.vocabulary = vocabulary;
    }

    @EventListener
    void on(DeckCardsRequested event) {
        event.reply().complete(vocabulary.getDeckCards(event.deckId()));
    }

    @EventListener
    void on(SenseCardRequested event) {
        event.reply().complete(vocabulary.getCardBySenseId(event.senseId()));
    }

    @EventListener
    void on(WordAccessRequested event) {
        vocabulary.getWordDetail(event.wordId());
        event.reply().complete(null);
    }

    @EventListener
    void on(ReviewCardValidationRequested event) {
        vocabulary.resolveReviewCard(
                event.senseId(),
                event.wordId(),
                event.revisionId(),
                event.deckId(),
                event.deckRevisionId());
        event.reply().complete(null);
    }

    @EventListener
    void on(ActiveDecksByIdsRequested event) {
        event.reply().complete(vocabulary.findActiveDecksByIds(event.deckIds()));
    }
}
