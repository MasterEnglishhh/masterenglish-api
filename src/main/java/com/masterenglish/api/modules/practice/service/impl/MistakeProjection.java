package com.masterenglish.api.modules.practice.service.impl;

import com.masterenglish.api.modules.practice.entity.MistakeRecord;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Folds one learner's graded history for one question into the mistake state (contract section 8,
 * SCHEMA section 6). The input must already be ordered by (effective_finished_at, attempt_id); SQL
 * does that ordering. The function is pure: no clock and no I/O, so it can be tested alone.
 */
final class MistakeProjection {

    private MistakeProjection() {}

    /** One graded answer of a submitted attempt. */
    record Event(Instant effectiveFinishedAt, UUID attemptId, UUID snapshotId, boolean correct) {}

    /** The projected fields of {@code mistake_records}. */
    record Result(
            String state,
            int wrongCount,
            int consecutiveCorrect,
            UUID sourceAttemptId,
            UUID sourceSnapshotId,
            Instant lastWrongAt,
            Instant lastEvaluatedAt,
            Instant resolvedAt) {}

    /**
     * Returns the state after the whole history, or {@code null} when the question was never
     * answered wrongly (no mistake record exists). A blank answer is a wrong answer.
     */
    static Result fold(List<Event> history) {
        String state = null;
        int wrong = 0;
        int consecutive = 0;
        Instant resolvedAt = null;
        Event lastWrong = null;
        Event last = null;
        for (Event event : history) {
            last = event;
            if (!event.correct()) {
                wrong++;
                lastWrong = event;
                state = MistakeRecord.OPEN;
                consecutive = 0;
                resolvedAt = null;
            } else if (MistakeRecord.OPEN.equals(state)) {
                state = MistakeRecord.IMPROVING;
                consecutive = 1;
            } else if (MistakeRecord.IMPROVING.equals(state)) {
                state = MistakeRecord.RESOLVED;
                consecutive = 2;
                resolvedAt = event.effectiveFinishedAt();
            } else if (MistakeRecord.RESOLVED.equals(state)) {
                consecutive++;
            }
        }
        if (lastWrong == null) {
            return null;
        }
        return new Result(
                state,
                wrong,
                consecutive,
                lastWrong.attemptId(),
                lastWrong.snapshotId(),
                lastWrong.effectiveFinishedAt(),
                last.effectiveFinishedAt(),
                resolvedAt);
    }
}
