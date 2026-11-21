package com.masterenglish.api.common.events;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Synchronous questions answered by the practice module (practice/CONTRACT.md section 2.1).
 *
 * <p>Declared only, in Stage 2 step A6. The practice listener is not implemented yet, so a call now
 * fails with IllegalStateException ("No synchronous reply"), which becomes a 500. Do not call these
 * methods until the handler exists (Stage 2 steps B3 and B4).
 */
@Component
public class PracticeEvents {

    private final ModuleEvents events;

    PracticeEvents(ModuleEvents events) {
        this.events = events;
    }

    /** Number of distinct eligible questions for the topics (contract section 2.2). */
    public long countAvailable(String category, Set<UUID> topicIds) {
        return events.request(reply -> new AvailableCountRequested(category, topicIds, reply));
    }

    /**
     * Deterministic selection of up to {@code limit} questions (contract section 7.2, D9). The
     * request id fixes the order, so the same request always gets the same questions. Throws
     * BusinessException (NO_ELIGIBLE_QUESTIONS or INSUFFICIENT_QUESTIONS) when there are too few,
     * and never returns a partial selection.
     */
    public List<SelectedQuestion> selectQuestions(
            UUID requestId, String category, Set<UUID> topicIds, int limit) {
        return events.request(
                reply -> new SelectionRequested(requestId, category, topicIds, limit, reply));
    }

    public record AvailableCountRequested(
            String category, Set<UUID> topicIds, EventReply<Long> reply) {}

    public record SelectionRequested(
            UUID requestId,
            String category,
            Set<UUID> topicIds,
            int limit,
            EventReply<List<SelectedQuestion>> reply) {}

    /**
     * One question as pinned to a revision. {@code correctOptionId} is for server-side snapshot
     * writing only and must never reach a learner-facing response.
     */
    public record SelectedQuestion(
            UUID questionId,
            UUID sourceRevisionId,
            Set<UUID> topicIds,
            UUID attributedTopicId,
            String questionTypeCode,
            String practiceMode,
            int schemaVersion,
            String gradingPolicyVersion,
            String prompt,
            String explanation,
            List<SelectedOption> options,
            UUID correctOptionId) {}

    public record SelectedOption(UUID optionId, int position, String body) {}
}
