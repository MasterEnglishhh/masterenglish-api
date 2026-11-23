package com.masterenglish.api.modules.practice.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.modules.practice.service.impl.SelectionPlanner.Candidate;
import com.masterenglish.api.modules.practice.service.impl.SelectionPlanner.Selection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class SelectionPlannerTests {

    // Golden inputs. Keys were computed outside Java with: printf '%s%s' R Q | sha256sum
    private static final UUID R1 = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");
    private static final UUID R2 = UUID.fromString("0f8fad5b-d9cb-469f-a165-708677289502");
    private static final UUID A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID C = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID Q1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID Q2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID Q3 = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID Q4 = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private static Candidate c(UUID question, UUID... topics) {
        return new Candidate(question, Set.of(topics));
    }

    @Test
    void matchesGoldenVectorComputedIndependently() {
        // Order by key: Q4, Q3, Q2, Q1. Q4 and Q2 tie on allocation, so the smaller UUID (A) wins.
        var result =
                SelectionPlanner.plan(
                        R1,
                        List.of(A, B),
                        4,
                        List.of(c(Q1, A), c(Q2, A, B), c(Q3, B), c(Q4, A, B)));

        assertThat(result)
                .containsExactly(
                        new Selection(Q4, A),
                        new Selection(Q3, B),
                        new Selection(Q2, A),
                        new Selection(Q1, A));
    }

    @Test
    void attributesToLeastAllocatedTopicNotToSmallestUuid() {
        // Order by key: Q1 then Q2. After Q1 goes to A, Q2 must go to B even though A < B.
        var result = SelectionPlanner.plan(R2, List.of(A, B), 2, List.of(c(Q1, A), c(Q2, A, B)));

        assertThat(result).containsExactly(new Selection(Q1, A), new Selection(Q2, B));
    }

    @Test
    void resultDoesNotDependOnTopicOrCandidateOrder() {
        List<Candidate> candidates =
                new ArrayList<>(List.of(c(Q1, A), c(Q2, A, B), c(Q3, B), c(Q4, A, B)));
        var forward = SelectionPlanner.plan(R1, List.of(A, B), 4, candidates);

        Collections.reverse(candidates);
        var reversed = SelectionPlanner.plan(R1, List.of(B, A), 4, candidates);

        assertThat(reversed).isEqualTo(forward);
    }

    @Test
    void requestingExactlyTheEligibleCountSelectsEveryEligibleQuestion() {
        var result =
                SelectionPlanner.plan(
                        R1, List.of(A, B), 2, List.of(c(Q1, A), c(Q2, A, B), c(Q3, C)));

        assertThat(result).extracting(Selection::questionId).containsExactlyInAnyOrder(Q1, Q2);
    }

    @Test
    void noEligibleQuestionsFailsWithZeroAvailable() {
        assertThatThrownBy(() -> SelectionPlanner.plan(R1, List.of(A), 5, List.of(c(Q1, B))))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> {
                            assertThat(ex.getErrorCode())
                                    .isEqualTo(ErrorCode.NO_ELIGIBLE_QUESTIONS);
                            assertThat(ex.getDetails())
                                    .containsEntry("requestedCount", 5)
                                    .containsEntry("availableCount", 0);
                        });
    }

    @Test
    void insufficientQuestionsNeverPartiallyStarts() {
        assertThatThrownBy(
                        () -> SelectionPlanner.plan(R1, List.of(A), 3, List.of(c(Q1, A), c(Q2, A))))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> {
                            assertThat(ex.getErrorCode())
                                    .isEqualTo(ErrorCode.INSUFFICIENT_QUESTIONS);
                            assertThat(ex.getDetails())
                                    .containsEntry("requestedCount", 3)
                                    .containsEntry("availableCount", 2);
                        });
    }

    @Test
    void availableCountCountsDistinctQuestionsNotTopicMemberships() {
        // Q2 matches both requested topics but must be counted once.
        assertThatThrownBy(
                        () ->
                                SelectionPlanner.plan(
                                        R1, List.of(A, B), 3, List.of(c(Q1, A), c(Q2, A, B))))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.getDetails()).containsEntry("availableCount", 2));
    }

    @Test
    void rejectsInvalidArguments() {
        List<Candidate> one = List.of(c(Q1, A));
        assertThatThrownBy(() -> SelectionPlanner.plan(R1, List.of(A), 0, one))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SelectionPlanner.plan(R1, List.of(), 1, one))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> SelectionPlanner.plan(R1, List.of(A), 2, List.of(c(Q1, A), c(Q1, A))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void randomCasesKeepEveryInvariantAndAreReproducible() {
        Random random = new Random(20261121L);
        List<UUID> pool = IntStream.rangeClosed(1, 6).mapToObj(i -> new UUID(0, i)).toList();

        for (int run = 0; run < 400; run++) {
            UUID requestId = new UUID(random.nextLong(), random.nextLong());
            List<UUID> shuffledPool = new ArrayList<>(pool);
            Collections.shuffle(shuffledPool, random);
            List<UUID> wanted = List.copyOf(shuffledPool.subList(0, 1 + random.nextInt(4)));
            int requested = 1 + random.nextInt(10);

            List<Candidate> candidates = new ArrayList<>();
            int count = random.nextInt(26);
            for (int i = 0; i < count; i++) {
                List<UUID> topics = new ArrayList<>(pool);
                Collections.shuffle(topics, random);
                Set<UUID> own = new HashSet<>(topics.subList(0, 1 + random.nextInt(3)));
                candidates.add(new Candidate(new UUID(run + 1L, i + 1L), own));
            }

            List<Candidate> eligible =
                    candidates.stream()
                            .filter(cand -> cand.topicIds().stream().anyMatch(wanted::contains))
                            .toList();

            if (eligible.isEmpty() || eligible.size() < requested) {
                assertThatThrownBy(
                                () ->
                                        SelectionPlanner.plan(
                                                requestId, wanted, requested, candidates))
                        .isInstanceOfSatisfying(
                                BusinessException.class,
                                ex -> {
                                    ErrorCode expected =
                                            eligible.isEmpty()
                                                    ? ErrorCode.NO_ELIGIBLE_QUESTIONS
                                                    : ErrorCode.INSUFFICIENT_QUESTIONS;
                                    assertThat(ex.getErrorCode()).isEqualTo(expected);
                                    assertThat(ex.getDetails())
                                            .containsEntry("availableCount", eligible.size());
                                });
                continue;
            }

            var result = SelectionPlanner.plan(requestId, wanted, requested, candidates);
            assertThatTheRuleIsKept(requestId, wanted, requested, candidates, result);

            Collections.shuffle(candidates, random);
            assertThat(SelectionPlanner.plan(requestId, wanted, requested, candidates))
                    .isEqualTo(result);
        }
    }

    /** Recomputes the contract from the output alone, independently of the planner's code path. */
    private static void assertThatTheRuleIsKept(
            UUID requestId,
            List<UUID> wanted,
            int requested,
            List<Candidate> candidates,
            List<Selection> result) {
        assertThat(result).hasSize(requested);
        assertThat(result.stream().map(Selection::questionId).distinct().count())
                .isEqualTo(requested);

        Map<UUID, Candidate> byId = new HashMap<>();
        candidates.forEach(cand -> byId.put(cand.questionId(), cand));

        // Chosen set is the first N eligible questions in key order.
        List<UUID> expectedOrder =
                candidates.stream()
                        .filter(cand -> cand.topicIds().stream().anyMatch(wanted::contains))
                        .sorted(
                                Comparator.comparing(
                                                (Candidate cand) ->
                                                        SelectionPlanner.orderKey(
                                                                requestId, cand.questionId()))
                                        .thenComparing(cand -> cand.questionId().toString()))
                        .limit(requested)
                        .map(Candidate::questionId)
                        .toList();
        assertThat(result.stream().map(Selection::questionId).toList()).isEqualTo(expectedOrder);

        // Each attribution is argmin over its requested topics of (allocated so far, topic).
        Map<UUID, Integer> allocated = new HashMap<>();
        wanted.forEach(topic -> allocated.put(topic, 0));
        for (Selection selection : result) {
            Candidate cand = byId.get(selection.questionId());
            UUID expected =
                    cand.topicIds().stream()
                            .filter(wanted::contains)
                            .min(
                                    Comparator.comparing((UUID topic) -> allocated.get(topic))
                                            .thenComparing(topic -> topic.toString()))
                            .orElseThrow();
            assertThat(selection.attributedTopicId()).isEqualTo(expected);
            assertThat(cand.topicIds()).contains(selection.attributedTopicId());
            allocated.merge(expected, 1, Integer::sum);
        }
        assertThat(allocated.values().stream().mapToInt(Integer::intValue).sum())
                .isEqualTo(requested);
    }
}
