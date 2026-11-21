package com.masterenglish.api.modules.practice.service.impl;

import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Deterministic topic allocation for one practice attempt (practice/CONTRACT.md section 7.2, D9).
 * Pure logic: no I/O, no clock, no randomness. The same request id and the same eligible set give
 * the same selection, whatever the order of the inputs.
 */
final class SelectionPlanner {

    /** A question with topics(q): the topic_ids of its pinned published revision. */
    record Candidate(UUID questionId, Set<UUID> topicIds) {}

    /** One chosen question, counted once under the topic it is attributed to. */
    record Selection(UUID questionId, UUID attributedTopicId) {}

    /** Ties on allocation are broken by the lowercase UUID string, as the contract requires. */
    private static final Comparator<UUID> TOPIC_ORDER = Comparator.comparing(UUID::toString);

    private SelectionPlanner() {}

    /**
     * @throws BusinessException NO_ELIGIBLE_QUESTIONS when no candidate matches a requested topic;
     *     INSUFFICIENT_QUESTIONS when fewer than {@code requested} distinct questions match. Never
     *     returns a partial selection.
     * @throws IllegalArgumentException for invalid input (programming error).
     */
    static List<Selection> plan(
            UUID requestId,
            Collection<UUID> requestedTopics,
            int requested,
            Collection<Candidate> candidates) {
        if (requested < 1) {
            throw new IllegalArgumentException("requested must be at least 1");
        }
        Set<UUID> wanted = new HashSet<>(requestedTopics);
        if (wanted.isEmpty()) {
            throw new IllegalArgumentException("at least one topic is required");
        }

        List<Keyed> eligible = eligible(requestId, wanted, candidates);
        if (eligible.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.NO_ELIGIBLE_QUESTIONS,
                    "No eligible questions.",
                    Map.of("requestedCount", requested, "availableCount", 0));
        }
        if (eligible.size() < requested) {
            throw new BusinessException(
                    ErrorCode.INSUFFICIENT_QUESTIONS,
                    "Not enough eligible questions.",
                    Map.of("requestedCount", requested, "availableCount", eligible.size()));
        }
        return attribute(eligible.subList(0, requested), wanted);
    }

    /** Ordering key of step 2: SHA-256 of the request id and question id, lowercase hex. */
    static String orderKey(UUID requestId, UUID questionId) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(
                                    (requestId.toString() + questionId.toString())
                                            .getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java runtime", e);
        }
    }

    private record Keyed(String key, Candidate candidate) {}

    private static List<Keyed> eligible(
            UUID requestId, Set<UUID> wanted, Collection<Candidate> candidates) {
        Set<UUID> seen = new HashSet<>();
        List<Keyed> out = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (!seen.add(candidate.questionId())) {
                throw new IllegalArgumentException("candidate questions must be distinct");
            }
            if (candidate.topicIds().stream().anyMatch(wanted::contains)) {
                out.add(new Keyed(orderKey(requestId, candidate.questionId()), candidate));
            }
        }
        out.sort(
                Comparator.comparing(Keyed::key)
                        .thenComparing(keyed -> keyed.candidate().questionId().toString()));
        return out;
    }

    /**
     * Step 4: each question goes to argmin over its requested topics of (allocated so far, topic).
     * Topics are scanned in ascending string order and the first minimum wins, so ties resolve to
     * the smaller UUID string.
     */
    private static List<Selection> attribute(List<Keyed> chosen, Set<UUID> wanted) {
        Map<UUID, Integer> allocated = new HashMap<>();
        for (UUID topic : wanted) {
            allocated.put(topic, 0);
        }
        List<Selection> out = new ArrayList<>(chosen.size());
        for (Keyed keyed : chosen) {
            UUID best = null;
            for (UUID topic :
                    keyed.candidate().topicIds().stream()
                            .filter(wanted::contains)
                            .sorted(TOPIC_ORDER)
                            .toList()) {
                if (best == null || allocated.get(topic) < allocated.get(best)) {
                    best = topic;
                }
            }
            allocated.merge(best, 1, Integer::sum);
            out.add(new Selection(keyed.candidate().questionId(), best));
        }
        return out;
    }
}
