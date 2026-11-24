package com.masterenglish.api.common.events;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class LearningEvents {
    private final ModuleEvents events;

    LearningEvents(ModuleEvents events) {
        this.events = events;
    }

    /** Due flashcard counts per learner; learners without due cards are absent from the map. */
    public Map<UUID, Long> countDueCards(Set<UUID> userIds, Instant asOf) {
        return events.request(
                reply -> new DueCardsCountRequested(Set.copyOf(userIds), asOf, reply));
    }

    public record DueCardsCountRequested(
            Set<UUID> userIds, Instant asOf, EventReply<Map<UUID, Long>> reply) {}
}
