package com.masterenglish.api.common.events;

import com.masterenglish.api.common.api.WordSenseCardDto;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class VocabularyEvents {
    private final ModuleEvents events;

    VocabularyEvents(ModuleEvents events) {
        this.events = events;
    }

    public List<WordSenseCardDto> getDeckCards(UUID deckId) {
        return events.request(reply -> new DeckCardsRequested(deckId, reply));
    }

    public Optional<WordSenseCardDto> getCardBySenseId(UUID senseId) {
        return events.request(reply -> new SenseCardRequested(senseId, reply));
    }

    public void requireWord(UUID wordId) {
        events.<Void>request(reply -> new WordAccessRequested(wordId, reply));
    }

    public void verifyReviewCard(
            UUID senseId, UUID wordId, UUID revisionId, UUID deckId, UUID deckRevisionId) {
        events.<Void>request(
                reply ->
                        new ReviewCardValidationRequested(
                                senseId, wordId, revisionId, deckId, deckRevisionId, reply));
    }

    public record DeckCardsRequested(UUID deckId, EventReply<List<WordSenseCardDto>> reply) {}

    public record SenseCardRequested(UUID senseId, EventReply<Optional<WordSenseCardDto>> reply) {}

    public record WordAccessRequested(UUID wordId, EventReply<Void> reply) {}

    public record ReviewCardValidationRequested(
            UUID senseId,
            UUID wordId,
            UUID revisionId,
            UUID deckId,
            UUID deckRevisionId,
            EventReply<Void> reply) {}
}
