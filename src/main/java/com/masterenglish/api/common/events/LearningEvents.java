package com.masterenglish.api.common.events;

import java.time.Instant;
import java.time.LocalDate;
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

    /** Active study seconds summed on each learner's given local day. */
    public Map<UUID, Long> sumDailyStudySeconds(Map<UUID, LocalDate> userLocalDays) {
        return events.request(
                reply -> new DailyStudySecondsRequested(Map.copyOf(userLocalDays), reply));
    }

    /** Active study seconds on {@code localDay} for one learner. */
    public long getDailyStudySeconds(UUID userId, LocalDate localDay) {
        return events.request(reply -> new UserDailyStudySecondsRequested(userId, localDay, reply));
    }

    public record DueCardsCountRequested(
            Set<UUID> userIds, Instant asOf, EventReply<Map<UUID, Long>> reply) {}

    public record DailyStudySecondsRequested(
            Map<UUID, LocalDate> userLocalDays, EventReply<Map<UUID, Long>> reply) {}

    public record UserDailyStudySecondsRequested(
            UUID userId, LocalDate localDay, EventReply<Long> reply) {}
}
