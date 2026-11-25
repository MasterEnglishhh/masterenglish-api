package com.masterenglish.api.modules.practice.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.common.events.GrammarEvents;
import com.masterenglish.api.common.events.IdentityEvents;
import com.masterenglish.api.modules.practice.dto.response.AnswerResponse;
import com.masterenglish.api.modules.practice.dto.response.AttemptResponse;
import com.masterenglish.api.modules.practice.dto.response.AttemptResponse.OptionView;
import com.masterenglish.api.modules.practice.dto.response.AttemptResponse.QuestionView;
import com.masterenglish.api.modules.practice.dto.response.ExerciseSummaryResponse;
import com.masterenglish.api.modules.practice.dto.response.StartAttemptResult;
import com.masterenglish.api.modules.practice.dto.response.StatisticsSummaryResponse;
import com.masterenglish.api.modules.practice.dto.response.TopicStatisticsResponse;
import com.masterenglish.api.modules.practice.entity.Attempt;
import com.masterenglish.api.modules.practice.entity.AttemptAnswer;
import com.masterenglish.api.modules.practice.entity.AttemptQuestionSnapshot;
import com.masterenglish.api.modules.practice.entity.AttemptSnapshotOption;
import com.masterenglish.api.modules.practice.entity.AttemptSourceMistake;
import com.masterenglish.api.modules.practice.entity.AttemptSourceTopic;
import com.masterenglish.api.modules.practice.entity.MistakeRecord;
import com.masterenglish.api.modules.practice.entity.Question;
import com.masterenglish.api.modules.practice.entity.QuestionOption;
import com.masterenglish.api.modules.practice.entity.QuestionTopic;
import com.masterenglish.api.modules.practice.repository.AttemptAnswerRepository;
import com.masterenglish.api.modules.practice.repository.AttemptQuestionSnapshotRepository;
import com.masterenglish.api.modules.practice.repository.AttemptRepository;
import com.masterenglish.api.modules.practice.repository.AttemptSnapshotOptionRepository;
import com.masterenglish.api.modules.practice.repository.AttemptSourceMistakeRepository;
import com.masterenglish.api.modules.practice.repository.AttemptSourceTopicRepository;
import com.masterenglish.api.modules.practice.repository.MistakeRecordRepository;
import com.masterenglish.api.modules.practice.repository.QuestionOptionRepository;
import com.masterenglish.api.modules.practice.repository.QuestionRepository;
import com.masterenglish.api.modules.practice.repository.QuestionTopicRepository;
import com.masterenglish.api.modules.practice.service.PracticeService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Grammar practice attempts (practice/CONTRACT.md, sections 5 to 8).
 *
 * <p>Lock order is always user, then attempt, then answer slot. Every write path first locks the
 * user, so starts, saves, submits and time-outs of one learner serialize.
 */
@Service
public class PracticeServiceImpl implements PracticeService {

    static final int MAX_QUESTION_COUNT = 100;
    static final int MAX_TOPICS = 10;
    static final int MIN_MIXED_TOPICS = 2;
    static final int MAX_OPEN_ATTEMPTS = 5;
    static final Duration ABANDON_AFTER = Duration.ofDays(7);
    static final String GRAMMAR_TOPIC = "GRAMMAR_TOPIC";
    static final String MIXED_GRAMMAR = "MIXED_GRAMMAR";
    static final String MISTAKES = "MISTAKES";
    static final String GRADING_POLICY = "SINGLE_CHOICE_V1";
    static final String DEFAULT_TIMEZONE = "Asia/Ho_Chi_Minh";

    private final IdentityEvents identity;
    private final GrammarEvents grammar;
    private final AttemptRepository attempts;
    private final AttemptSourceTopicRepository sourceTopics;
    private final AttemptSourceMistakeRepository sourceMistakes;
    private final AttemptQuestionSnapshotRepository snapshots;
    private final AttemptSnapshotOptionRepository snapshotOptions;
    private final AttemptAnswerRepository answers;
    private final QuestionRepository questions;
    private final QuestionOptionRepository questionOptions;
    private final QuestionTopicRepository questionTopics;
    private final MistakeRecordRepository mistakes;
    private final MistakeRecorder recorder;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PracticeServiceImpl(
            IdentityEvents identity,
            GrammarEvents grammar,
            AttemptRepository attempts,
            AttemptSourceTopicRepository sourceTopics,
            AttemptSourceMistakeRepository sourceMistakes,
            AttemptQuestionSnapshotRepository snapshots,
            AttemptSnapshotOptionRepository snapshotOptions,
            AttemptAnswerRepository answers,
            QuestionRepository questions,
            QuestionOptionRepository questionOptions,
            QuestionTopicRepository questionTopics,
            MistakeRecordRepository mistakes,
            MistakeRecorder recorder,
            ObjectMapper objectMapper,
            Clock clock) {
        this.identity = identity;
        this.grammar = grammar;
        this.attempts = attempts;
        this.sourceTopics = sourceTopics;
        this.sourceMistakes = sourceMistakes;
        this.snapshots = snapshots;
        this.snapshotOptions = snapshotOptions;
        this.answers = answers;
        this.questions = questions;
        this.questionOptions = questionOptions;
        this.questionTopics = questionTopics;
        this.mistakes = mistakes;
        this.recorder = recorder;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public ExerciseSummaryResponse exercises(UUID topicId) {
        requireVisibleTopic(topicId);
        return new ExerciseSummaryResponse(topicId, questions.countEligibleByTopicId(topicId));
    }

    @Override
    @Transactional
    public StartAttemptResult start(
            UUID userId,
            UUID requestId,
            String sourceType,
            UUID topicId,
            List<UUID> topicIds,
            List<UUID> mistakeIds,
            Integer questionCount) {
        identity.verifyAndLockUser(userId);
        int count = validCount(questionCount);
        Source source = normalize(sourceType, topicId, topicIds, mistakeIds);
        String hash = sha256(source.canonical() + "|" + count);

        Optional<Attempt> replayed = attempts.findByUserIdAndRequestId(userId, requestId);
        if (replayed.isPresent()) {
            if (!replayed.get().getRequestHash().equals(hash)) {
                throw new BusinessException(
                        ErrorCode.IDEMPOTENCY_CONFLICT,
                        "This Idempotency-Key was already used for a different request.");
            }
            Attempt existing = lockOwned(userId, replayed.get().getId());
            settle(existing, clock.instant());
            return new StartAttemptResult(view(existing), false);
        }

        if (!source.mistakes()) {
            source.topics().forEach(this::requireVisibleTopic);
        }
        Instant now = clock.instant();
        attempts.lockOpenByUserId(userId).forEach(open -> settle(open, now));
        long open = attempts.countByUserIdAndStatus(userId, Attempt.IN_PROGRESS);
        if (open >= MAX_OPEN_ATTEMPTS) {
            throw new BusinessException(
                    ErrorCode.OPEN_ATTEMPT_LIMIT_REACHED,
                    "Too many attempts are open. Submit or finish one before starting another.",
                    Map.of("openAttemptCount", (int) open, "maxOpenAttempts", MAX_OPEN_ATTEMPTS));
        }

        Pool pool =
                source.mistakes()
                        ? mistakePool(userId, source.mistakeIds())
                        : topicPool(source.topics());
        if (pool.candidates().isEmpty()) {
            throw noEligible(count);
        }
        List<SelectionPlanner.Selection> chosen =
                SelectionPlanner.plan(requestId, pool.wanted(), count, pool.candidates());
        return seal(userId, requestId, hash, source, count, chosen, pool, now);
    }

    @Override
    @Transactional
    public AttemptResponse getAttempt(UUID userId, UUID attemptId) {
        identity.verifyAndLockUser(userId);
        Attempt attempt = lockOwned(userId, attemptId);
        settle(attempt, clock.instant());
        return view(attempt);
    }

    @Override
    @Transactional
    public AnswerResponse saveAnswer(
            UUID userId,
            UUID attemptId,
            UUID snapshotId,
            UUID selectedOptionId,
            long expectedVersion) {
        identity.verifyAndLockUser(userId);
        Attempt attempt = lockOwned(userId, attemptId);
        Instant now = clock.instant();
        settle(attempt, now);
        if (!attempt.isInProgress()) {
            throw notOpen(attempt);
        }
        snapshots
                .findByIdAndAttemptId(snapshotId, attemptId)
                .orElseThrow(() -> notFound("Question not found in this attempt."));
        if (selectedOptionId != null
                && !snapshotOptions.existsByIdSnapshotIdAndIdOptionId(
                        snapshotId, selectedOptionId)) {
            throw new BusinessException(
                    ErrorCode.OPTION_NOT_IN_SNAPSHOT,
                    "The option does not belong to this question.");
        }
        answers.lockBySnapshotId(snapshotId)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "Answer slot missing for a sealed question."));

        // Touch first: the bulk update below clears the persistence context.
        attempt.touch(now);
        int updated =
                selectedOptionId == null
                        ? answers.clearIfVersion(snapshotId, expectedVersion, now)
                        : answers.chooseIfVersion(
                                snapshotId, expectedVersion, selectedOptionId, now);
        AttemptAnswer current =
                answers.findBySnapshotId(snapshotId)
                        .orElseThrow(() -> new IllegalStateException("Answer slot missing."));
        if (updated == 0) {
            Map<String, Object> details = new HashMap<>();
            details.put("currentVersion", current.getVersion());
            details.put("currentSelectedOptionId", current.getSelectedOptionId());
            throw new BusinessException(
                    ErrorCode.STALE_ANSWER_VERSION,
                    "The answer changed since it was loaded. Reload the attempt and try again.",
                    details);
        }
        return new AnswerResponse(
                snapshotId,
                current.getSelectedOptionId(),
                current.getVersion(),
                current.getAnsweredAt(),
                attempt.getVersion());
    }

    @Override
    @Transactional
    public AttemptResponse submit(UUID userId, UUID attemptId) {
        identity.verifyAndLockUser(userId);
        Attempt attempt = lockOwned(userId, attemptId);
        Instant now = clock.instant();
        settle(attempt, now);
        if (attempt.isSubmitted()) {
            return view(attempt);
        }
        if (attempt.isAbandoned()) {
            throw notOpen(attempt);
        }
        finish(attempt, Attempt.MANUAL, now, now);
        return view(attempt);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TopicStatisticsResponse> topicStatistics(
            UUID userId, String category, String statisticsView) {
        requireAllocationGrammar(category, statisticsView);
        Instant since = windowStart();
        return attempts.topicStatistics(userId, since).stream()
                .map(
                        s -> {
                            Integer accuracy =
                                    s.getQuestionCount() == 0
                                            ? null
                                            : percent(s.getCorrectCount(), s.getQuestionCount());
                            Integer windowAccuracy =
                                    s.getWindowQuestionCount() == 0
                                            ? null
                                            : percent(
                                                    s.getWindowCorrectCount(),
                                                    s.getWindowQuestionCount());
                            return new TopicStatisticsResponse(
                                    s.getTopicId(),
                                    "allocation",
                                    s.getAttemptCount(),
                                    s.getQuestionCount(),
                                    s.getCorrectCount(),
                                    accuracy,
                                    s.getWindowQuestionCount(),
                                    s.getWindowCorrectCount(),
                                    windowAccuracy,
                                    WeakTopicPolicy.isWeak(
                                            s.getWindowQuestionCount(), s.getWindowCorrectCount()),
                                    WeakTopicPolicy.WINDOW_DAYS,
                                    since);
                        })
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public StatisticsSummaryResponse statisticsSummary(
            UUID userId, String category, String statisticsView) {
        requireAllocationGrammar(category, statisticsView);
        Instant since = windowStart();
        AttemptRepository.SummaryRow row = attempts.statisticsSummary(userId, since);
        return new StatisticsSummaryResponse(
                "allocation",
                row.getQuestionCount(),
                row.getCorrectCount(),
                row.getQuestionCount() == 0
                        ? null
                        : percent(row.getCorrectCount(), row.getQuestionCount()),
                row.getWindowQuestionCount(),
                row.getWindowCorrectCount(),
                row.getWindowQuestionCount() == 0
                        ? null
                        : percent(row.getWindowCorrectCount(), row.getWindowQuestionCount()),
                WeakTopicPolicy.WINDOW_DAYS,
                since);
    }

    private static void requireAllocationGrammar(String category, String statisticsView) {
        if (!Attempt.GRAMMAR.equals(category) || !"allocation".equals(statisticsView)) {
            throw validation("category must be GRAMMAR and view must be allocation.");
        }
    }

    private Instant windowStart() {
        return clock.instant().minus(Duration.ofDays(WeakTopicPolicy.WINDOW_DAYS));
    }

    /**
     * Time-out and abandon rules, applied under the attempt lock before any read or write. A timed
     * attempt past its deadline is submitted as TIME_LIMIT at the deadline. An untimed attempt idle
     * for {@link #ABANDON_AFTER} is abandoned, with no score. Closed attempts are never changed.
     */
    private void settle(Attempt attempt, Instant now) {
        if (!attempt.isInProgress()) {
            return;
        }
        if (attempt.getDeadlineAt() != null) {
            if (!attempt.getDeadlineAt().isAfter(now)) {
                finish(attempt, Attempt.TIME_LIMIT, attempt.getDeadlineAt(), now);
            }
            return;
        }
        if (!attempt.getLastActiveAt().isAfter(now.minus(ABANDON_AFTER))) {
            attempt.abandon(now);
        }
    }

    /**
     * Grades every slot from the sealed answer keys, closes the attempt, and rebuilds the mistake
     * projection of each question in it. All of this commits with the submission.
     */
    private void finish(Attempt attempt, String reason, Instant effectiveFinishedAt, Instant now) {
        Map<UUID, AttemptAnswer> bySnapshot = new HashMap<>();
        for (AttemptAnswer answer : answers.findByAttemptId(attempt.getId())) {
            bySnapshot.put(answer.getSnapshotId(), answer);
        }
        int correct = 0;
        int answered = 0;
        List<AttemptQuestionSnapshot> sealed =
                snapshots.findByAttemptIdOrderByOrdinalAsc(attempt.getId());
        for (AttemptQuestionSnapshot snapshot : sealed) {
            AttemptAnswer answer = bySnapshot.get(snapshot.getId());
            UUID selected = answer.getSelectedOptionId();
            boolean isCorrect = selected != null && selected.equals(correctOptionId(snapshot));
            answer.grade(isCorrect);
            if (selected != null) {
                answered++;
            }
            if (isCorrect) {
                correct++;
            }
        }
        attempt.submit(
                reason,
                effectiveFinishedAt,
                now,
                correct,
                answered,
                ZoneId.of(attempt.getTimezone()));
        recorder.refresh(
                attempt.getUserId(),
                sealed.stream().map(AttemptQuestionSnapshot::getQuestionId).distinct().toList());
    }

    /** Pool for a topic-based source: eligible questions and all of their topics. */
    private Pool topicPool(List<UUID> topics) {
        Map<UUID, Set<UUID>> sets = topicSetsOf(questions.lockEligibleQuestionIds(topics));
        return new Pool(topics, toCandidates(sets), Map.of());
    }

    /**
     * Pool for MISTAKES: the learner must own every id (anything else is 404), and the question
     * must still be the visible revision, enabled and not archived.
     */
    private Pool mistakePool(UUID userId, List<UUID> mistakeIds) {
        List<MistakeRecord> owned = mistakes.findByUserIdAndIdIn(userId, mistakeIds);
        if (owned.size() != mistakeIds.size()) {
            throw notFound("Mistake not found.");
        }
        Map<UUID, UUID> mistakeByQuestion =
                owned.stream()
                        .collect(
                                Collectors.toMap(
                                        MistakeRecord::getQuestionId, MistakeRecord::getId));
        Map<UUID, Set<UUID>> sets =
                topicSetsOf(questions.lockEligibleQuestionIdsIn(mistakeByQuestion.keySet()));
        sets.entrySet().removeIf(e -> e.getValue().isEmpty());
        Set<UUID> wanted = new HashSet<>();
        sets.values().forEach(wanted::addAll);
        return new Pool(List.copyOf(wanted), toCandidates(sets), mistakeByQuestion);
    }

    private Map<UUID, Set<UUID>> topicSetsOf(Collection<UUID> questionIds) {
        Map<UUID, Set<UUID>> sets = new HashMap<>();
        for (UUID id : questionIds) {
            sets.put(id, new HashSet<>());
        }
        if (questionIds.isEmpty()) {
            return sets;
        }
        for (QuestionTopic link : questionTopics.findByQuestionIds(questionIds)) {
            sets.computeIfAbsent(link.getId().getQuestionId(), k -> new HashSet<>())
                    .add(link.getId().getTopicId());
        }
        return sets;
    }

    private static List<SelectionPlanner.Candidate> toCandidates(Map<UUID, Set<UUID>> sets) {
        return sets.entrySet().stream()
                .map(e -> new SelectionPlanner.Candidate(e.getKey(), e.getValue()))
                .toList();
    }

    /** Writes the attempt, its topics or mistakes, and every snapshot, then seals it. */
    private StartAttemptResult seal(
            UUID userId,
            UUID requestId,
            String hash,
            Source source,
            int count,
            List<SelectionPlanner.Selection> chosen,
            Pool pool,
            Instant now) {
        List<UUID> chosenIds = chosen.stream().map(SelectionPlanner.Selection::questionId).toList();
        Map<UUID, Question> loaded =
                questions.findAllById(chosenIds).stream()
                        .collect(Collectors.toMap(Question::getId, Function.identity()));
        Map<UUID, List<QuestionOption>> optionsByQuestion = new HashMap<>();
        for (QuestionOption option : questionOptions.findByQuestionIdIn(chosenIds)) {
            optionsByQuestion
                    .computeIfAbsent(option.getQuestionId(), k -> new java.util.ArrayList<>())
                    .add(option);
        }
        Map<UUID, UUID> revisionByTopic = new HashMap<>();
        if (!source.mistakes()) {
            for (UUID topic : source.topics()) {
                GrammarEvents.PublishedTopicRef ref =
                        grammar.findPublishedTopic(topic)
                                .orElseThrow(() -> notFound("Grammar topic not found."));
                revisionByTopic.put(topic, ref.revisionId());
            }
        }

        boolean single = GRAMMAR_TOPIC.equals(source.type());
        UUID attemptId = UUID.randomUUID();
        UUID sourceTopicId = single ? source.topics().get(0) : null;
        Attempt attempt =
                new Attempt(
                        attemptId,
                        userId,
                        source.type(),
                        sourceTopicId,
                        sourceTopicId == null ? null : revisionByTopic.get(sourceTopicId),
                        sourceConfig(source, count),
                        requestId,
                        hash,
                        (short) chosen.size(),
                        DEFAULT_TIMEZONE,
                        now);
        attempts.save(attempt);
        if (MIXED_GRAMMAR.equals(source.type())) {
            for (UUID topic : source.topics()) {
                sourceTopics.save(
                        new AttemptSourceTopic(attemptId, topic, revisionByTopic.get(topic)));
            }
        }
        if (source.mistakes()) {
            for (SelectionPlanner.Selection selection : chosen) {
                UUID mistakeId = pool.mistakeByQuestion().get(selection.questionId());
                sourceMistakes.save(new AttemptSourceMistake(attemptId, userId, mistakeId));
            }
        }

        Map<UUID, Set<UUID>> topicSets = new HashMap<>();
        pool.candidates().forEach(c -> topicSets.put(c.questionId(), c.topicIds()));
        short ordinal = 1;
        for (SelectionPlanner.Selection selection : chosen) {
            Question question = loaded.get(selection.questionId());
            List<QuestionOption> options =
                    optionsByQuestion.getOrDefault(question.getId(), List.of()).stream()
                            .sorted(Comparator.comparingInt(QuestionOption::getPosition))
                            .toList();
            UUID correct =
                    options.stream()
                            .filter(QuestionOption::isCorrect)
                            .map(QuestionOption::getId)
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "Question has no correct option."));
            UUID snapshotId = UUID.randomUUID();
            snapshots.save(
                    new AttemptQuestionSnapshot(
                            snapshotId,
                            attemptId,
                            question.getId(),
                            question.getPublishedRevisionId(),
                            ordinal,
                            question.getQuestionTypeCode(),
                            GRADING_POLICY,
                            questionSnapshot(
                                    question,
                                    selection.attributedTopicId(),
                                    topicSets.getOrDefault(question.getId(), Set.of())),
                            json("correctOptionId", correct.toString()),
                            json("explanation", question.getExplanation())));
            for (QuestionOption option : options) {
                snapshotOptions.save(
                        new AttemptSnapshotOption(
                                snapshotId,
                                option.getId(),
                                (short) option.getPosition(),
                                option.getBody()));
            }
            answers.save(new AttemptAnswer(UUID.randomUUID(), attemptId, snapshotId, now));
            ordinal++;
        }
        attempt.seal(now);
        return new StartAttemptResult(view(attempt), true);
    }

    private AttemptResponse view(Attempt attempt) {
        UUID attemptId = attempt.getId();
        List<AttemptQuestionSnapshot> sealed =
                snapshots.findByAttemptIdOrderByOrdinalAsc(attemptId);
        List<UUID> snapshotIds = sealed.stream().map(AttemptQuestionSnapshot::getId).toList();
        Map<UUID, List<AttemptSnapshotOption>> optionsBySnapshot = new HashMap<>();
        if (!snapshotIds.isEmpty()) {
            for (AttemptSnapshotOption option :
                    snapshotOptions.findByIdSnapshotIdInOrderByPositionAsc(snapshotIds)) {
                optionsBySnapshot
                        .computeIfAbsent(
                                option.getId().getSnapshotId(), k -> new java.util.ArrayList<>())
                        .add(option);
            }
        }
        Map<UUID, AttemptAnswer> answerBySnapshot = new HashMap<>();
        for (AttemptAnswer answer : answers.findByAttemptId(attemptId)) {
            answerBySnapshot.put(answer.getSnapshotId(), answer);
        }
        boolean revealed = attempt.isSubmitted();
        List<QuestionView> questionViews =
                sealed.stream()
                        .map(
                                snapshot ->
                                        questionView(
                                                snapshot,
                                                optionsBySnapshot.getOrDefault(
                                                        snapshot.getId(), List.of()),
                                                answerBySnapshot.get(snapshot.getId()),
                                                revealed))
                        .toList();
        List<UUID> topicIds;
        if (MIXED_GRAMMAR.equals(attempt.getSourceType())) {
            topicIds =
                    sourceTopics.findByIdAttemptIdOrderByIdTopicIdAsc(attemptId).stream()
                            .map(t -> t.getId().getTopicId())
                            .toList();
        } else if (MISTAKES.equals(attempt.getSourceType())) {
            topicIds =
                    sealed.stream()
                            .map(
                                    s ->
                                            UUID.fromString(
                                                    readTree(s.getQuestionSnapshot())
                                                            .path("attributedTopicId")
                                                            .asText()))
                            .distinct()
                            .sorted(Comparator.comparing(UUID::toString))
                            .toList();
        } else {
            topicIds = List.of(attempt.getSourceTopicId());
        }
        return new AttemptResponse(
                attemptId,
                attempt.getCategory(),
                new AttemptResponse.Source(attempt.getSourceType(), topicIds),
                attempt.getStatus(),
                attempt.getTotalCount(),
                attempt.getVersion(),
                attempt.getDeadlineAt(),
                attempt.getStartedAt(),
                attempt.getLastActiveAt(),
                toInt(attempt.getScore()),
                toInt(attempt.getCorrectCount()),
                toInt(attempt.getAnsweredCount()),
                revealed ? percent(attempt.getCorrectCount(), attempt.getTotalCount()) : null,
                attempt.getSubmittedAt(),
                attempt.getEffectiveFinishedAt(),
                attempt.getSubmitReason(),
                attempt.getDurationSeconds(),
                questionViews);
    }

    /** Before submission the view carries no answer key and no explanation. */
    private QuestionView questionView(
            AttemptQuestionSnapshot snapshot,
            List<AttemptSnapshotOption> options,
            AttemptAnswer answer,
            boolean revealed) {
        JsonNode body = readTree(snapshot.getQuestionSnapshot());
        UUID correct = revealed ? correctOptionId(snapshot) : null;
        return new QuestionView(
                snapshot.getId(),
                snapshot.getOrdinal(),
                UUID.fromString(body.path("attributedTopicId").asText()),
                snapshot.getQuestionTypeCode(),
                body.path("practiceMode").asText(),
                body.path("prompt").asText(),
                options.stream()
                        .map(
                                o ->
                                        new OptionView(
                                                o.getId().getOptionId(),
                                                o.getPosition(),
                                                o.getPublicBody()))
                        .toList(),
                answer.getSelectedOptionId(),
                answer.getVersion(),
                revealed ? answer.getCorrect() : null,
                correct,
                revealed
                        ? readTree(snapshot.getExplanationSnapshot()).path("explanation").asText()
                        : null);
    }

    private Attempt lockOwned(UUID userId, UUID attemptId) {
        return attempts.lockByIdAndUserId(attemptId, userId)
                .orElseThrow(() -> notFound("Attempt not found."));
    }

    private void requireVisibleTopic(UUID topicId) {
        if (!grammar.isVisibleTopic(topicId)) {
            throw notFound("Grammar topic not found.");
        }
    }

    /**
     * Validates the source and returns it in canonical order. Topic sources and the mistake source
     * are mutually exclusive; each one rejects the fields that belong to the other.
     */
    private static Source normalize(
            String sourceType, UUID topicId, List<UUID> topicIds, List<UUID> mistakeIds) {
        boolean noMistakes = mistakeIds == null || mistakeIds.isEmpty();
        boolean noTopics = topicIds == null || topicIds.isEmpty();
        if (GRAMMAR_TOPIC.equals(sourceType)) {
            if (topicId == null || !noTopics || !noMistakes) {
                throw validation("GRAMMAR_TOPIC takes exactly one topicId.");
            }
            return new Source(sourceType, List.of(topicId), List.of());
        }
        if (MIXED_GRAMMAR.equals(sourceType)) {
            if (topicId != null || topicIds == null || !noMistakes) {
                throw validation("MIXED_GRAMMAR takes topicIds only.");
            }
            if (topicIds.size() < MIN_MIXED_TOPICS || topicIds.size() > MAX_TOPICS) {
                throw validation("MIXED_GRAMMAR needs 2 to 10 topics.");
            }
            if (topicIds.stream().anyMatch(Objects::isNull)
                    || topicIds.stream().distinct().count() != topicIds.size()) {
                throw validation("topicIds must be distinct and must not be null.");
            }
            return new Source(
                    sourceType,
                    topicIds.stream().sorted(Comparator.comparing(UUID::toString)).toList(),
                    List.of());
        }
        if (MISTAKES.equals(sourceType)) {
            if (topicId != null || !noTopics) {
                throw validation("MISTAKES takes mistakeIds only.");
            }
            if (mistakeIds == null
                    || mistakeIds.isEmpty()
                    || mistakeIds.size() > MAX_QUESTION_COUNT) {
                throw validation("mistakeIds must hold 1 to 100 ids.");
            }
            if (mistakeIds.stream().anyMatch(Objects::isNull)
                    || mistakeIds.stream().distinct().count() != mistakeIds.size()) {
                throw validation("mistakeIds must be distinct and must not be null.");
            }
            return new Source(
                    sourceType,
                    List.of(),
                    mistakeIds.stream().sorted(Comparator.comparing(UUID::toString)).toList());
        }
        throw validation("source.type must be GRAMMAR_TOPIC, MIXED_GRAMMAR or MISTAKES.");
    }

    private static int validCount(Integer questionCount) {
        if (questionCount == null || questionCount < 1 || questionCount > MAX_QUESTION_COUNT) {
            throw validation("questionCount must be between 1 and 100.");
        }
        return questionCount;
    }

    private static String joinIds(Collection<UUID> ids) {
        return ids.stream().map(UUID::toString).collect(Collectors.joining(","));
    }

    private static Integer toInt(Short value) {
        return value == null ? null : value.intValue();
    }

    static int percent(long part, long whole) {
        if (whole == 0) {
            return 0;
        }
        return BigDecimal.valueOf(part)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(whole), 0, RoundingMode.HALF_UP)
                .intValueExact();
    }

    private String sourceConfig(Source source, int count) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("sourceType", source.type());
        if (source.mistakes()) {
            ArrayNode ids = node.putArray("mistakeIds");
            source.mistakeIds().forEach(m -> ids.add(m.toString()));
        } else {
            ArrayNode ids = node.putArray("topicIds");
            source.topics().forEach(t -> ids.add(t.toString()));
        }
        node.put("questionCount", count);
        return write(node);
    }

    private String questionSnapshot(Question question, UUID attributedTopic, Set<UUID> topics) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("prompt", question.getPrompt());
        node.put("practiceMode", question.getPracticeMode());
        node.put("questionTypeCode", question.getQuestionTypeCode());
        ArrayNode ids = node.putArray("topicIds");
        topics.stream().map(UUID::toString).sorted().forEach(ids::add);
        node.put("attributedTopicId", attributedTopic.toString());
        return write(node);
    }

    private String json(String field, String value) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put(field, value);
        return write(node);
    }

    private UUID correctOptionId(AttemptQuestionSnapshot snapshot) {
        return UUID.fromString(
                readTree(snapshot.getAnswerKeySnapshot()).path("correctOptionId").asText());
    }

    private String write(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize a practice snapshot.", e);
        }
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored practice snapshot is not valid JSON.", e);
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available.", ex);
        }
    }

    private static BusinessException noEligible(int requested) {
        return new BusinessException(
                ErrorCode.NO_ELIGIBLE_QUESTIONS,
                "No eligible questions.",
                Map.of("requestedCount", requested, "availableCount", 0));
    }

    private static BusinessException notOpen(Attempt attempt) {
        return new BusinessException(
                ErrorCode.ATTEMPT_NOT_OPEN,
                "The attempt is already closed.",
                Map.of("attemptStatus", attempt.getStatus()));
    }

    private static BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, message);
    }

    private static BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message);
    }

    /** A validated source in canonical order. Only one of the two lists is filled. */
    private record Source(String type, List<UUID> topics, List<UUID> mistakeIds) {

        boolean mistakes() {
            return MISTAKES.equals(type);
        }

        String canonical() {
            return mistakes() ? MISTAKES + "|" + joinIds(mistakeIds) : type + "|" + joinIds(topics);
        }
    }

    /**
     * Eligible questions with their topic sets, the topics the selection may attribute to, and for
     * MISTAKES the mistake each question came from.
     */
    private record Pool(
            Collection<UUID> wanted,
            List<SelectionPlanner.Candidate> candidates,
            Map<UUID, UUID> mistakeByQuestion) {}
}
