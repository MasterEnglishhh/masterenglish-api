package com.masterenglish.api.modules.practice.service.impl;

import com.masterenglish.api.modules.practice.entity.MistakeRecord;
import com.masterenglish.api.modules.practice.repository.MistakeRecordRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Keeps mistake_records in step with the graded history. Called by PracticeServiceImpl inside the
 * same transaction that submits an attempt, so the projection and the grading commit together.
 *
 * <p>Each question is rebuilt from its full history, not updated incrementally. A late time-limit
 * submission can therefore land before a later attempt; the result is the same no matter when the
 * submission arrives, and a retry changes nothing.
 */
@Component
class MistakeRecorder {

    private final MistakeRecordRepository mistakes;
    private final Clock clock;

    MistakeRecorder(MistakeRecordRepository mistakes, Clock clock) {
        this.mistakes = mistakes;
        this.clock = clock;
    }

    void refresh(UUID userId, Collection<UUID> questionIds) {
        mistakes.flush();
        Instant now = clock.instant();
        for (UUID questionId : questionIds) {
            List<MistakeProjection.Event> history =
                    mistakes.findSubmittedHistory(userId, questionId).stream()
                            .map(
                                    row ->
                                            new MistakeProjection.Event(
                                                    row.getEffectiveFinishedAt(),
                                                    row.getAttemptId(),
                                                    row.getSnapshotId(),
                                                    row.getScore() != null && row.getScore() == 1))
                            .toList();
            MistakeProjection.Result result = MistakeProjection.fold(history);
            if (result == null) {
                continue;
            }
            MistakeRecord record =
                    mistakes.findByUserIdAndQuestionId(userId, questionId)
                            .orElseGet(
                                    () ->
                                            MistakeRecord.create(
                                                    UUID.randomUUID(), userId, questionId, now));
            record.apply(
                    result.state(),
                    result.wrongCount(),
                    result.consecutiveCorrect(),
                    result.sourceAttemptId(),
                    result.sourceSnapshotId(),
                    result.lastWrongAt(),
                    result.lastEvaluatedAt(),
                    result.resolvedAt(),
                    now);
            mistakes.save(record);
        }
    }
}
