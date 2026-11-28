package com.masterenglish.api.modules.practice.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.masterenglish.api.modules.practice.entity.MistakeRecord;
import com.masterenglish.api.modules.practice.service.impl.MistakeProjection.Event;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MistakeProjectionTests {

    private static final Instant T1 = Instant.parse("2026-11-01T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-11-02T10:00:00Z");
    private static final Instant T3 = Instant.parse("2026-11-03T10:00:00Z");
    private static final Instant T4 = Instant.parse("2026-11-04T10:00:00Z");

    private static Event event(Instant at, boolean correct) {
        return new Event(at, UUID.randomUUID(), UUID.randomUUID(), correct);
    }

    @Test
    void neverWrongLeavesNoMistake() {
        assertThat(MistakeProjection.fold(List.of(event(T1, true), event(T2, true)))).isNull();
        assertThat(MistakeProjection.fold(List.of())).isNull();
    }

    @Test
    void wrongThenCorrectTwiceWalksOpenImprovingResolved() {
        MistakeProjection.Result open = MistakeProjection.fold(List.of(event(T1, false)));
        assertThat(open.state()).isEqualTo(MistakeRecord.OPEN);
        assertThat(open.consecutiveCorrect()).isZero();
        assertThat(open.resolvedAt()).isNull();

        MistakeProjection.Result improving =
                MistakeProjection.fold(List.of(event(T1, false), event(T2, true)));
        assertThat(improving.state()).isEqualTo(MistakeRecord.IMPROVING);
        assertThat(improving.consecutiveCorrect()).isEqualTo(1);
        assertThat(improving.resolvedAt()).isNull();

        MistakeProjection.Result resolved =
                MistakeProjection.fold(List.of(event(T1, false), event(T2, true), event(T3, true)));
        assertThat(resolved.state()).isEqualTo(MistakeRecord.RESOLVED);
        assertThat(resolved.consecutiveCorrect()).isEqualTo(2);
        assertThat(resolved.resolvedAt()).isEqualTo(T3);
        assertThat(resolved.lastEvaluatedAt()).isEqualTo(T3);
    }

    @Test
    void resolvedStaysResolvedAndCountsOnUntilAWrongAnswerReopensIt() {
        MistakeProjection.Result longRun =
                MistakeProjection.fold(
                        List.of(
                                event(T1, false),
                                event(T2, true),
                                event(T3, true),
                                event(T4, true)));
        assertThat(longRun.state()).isEqualTo(MistakeRecord.RESOLVED);
        assertThat(longRun.consecutiveCorrect()).isEqualTo(3);
        assertThat(longRun.resolvedAt()).isEqualTo(T3);

        MistakeProjection.Result reopened =
                MistakeProjection.fold(
                        List.of(
                                event(T1, false),
                                event(T2, true),
                                event(T3, true),
                                event(T4, false)));
        assertThat(reopened.state()).isEqualTo(MistakeRecord.OPEN);
        assertThat(reopened.wrongCount()).isEqualTo(2);
        assertThat(reopened.consecutiveCorrect()).isZero();
        assertThat(reopened.resolvedAt()).isNull();
        assertThat(reopened.lastWrongAt()).isEqualTo(T4);
    }

    @Test
    void outcomeFollowsTheEffectiveTimeOrderOfTheHistory() {
        // The caller orders the history by effective time. The fold trusts that order, so the
        // outcome
        // depends on when answers took effect, not on when they were submitted.
        MistakeProjection.Result ordered =
                MistakeProjection.fold(List.of(event(T1, false), event(T2, true)));
        assertThat(ordered.state()).isEqualTo(MistakeRecord.IMPROVING);
        assertThat(ordered.lastWrongAt()).isEqualTo(T1);
        assertThat(ordered.lastEvaluatedAt()).isEqualTo(T2);
    }
}
