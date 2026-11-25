package com.masterenglish.api.modules.practice.service.impl;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.common.events.GrammarEvents;
import com.masterenglish.api.common.events.IdentityEvents;
import com.masterenglish.api.common.events.PracticeEvents.AttemptSummaryView;
import com.masterenglish.api.common.events.PracticeEvents.PracticeSummaryView;
import com.masterenglish.api.common.events.PracticeEvents.TopicAccuracyView;
import com.masterenglish.api.modules.practice.dto.response.AttemptResponse;
import com.masterenglish.api.modules.practice.dto.response.AttemptResponse.OptionResponse;
import com.masterenglish.api.modules.practice.dto.response.AttemptResponse.QuestionResponse;
import com.masterenglish.api.modules.practice.dto.response.AttemptSummaryResponse;
import com.masterenglish.api.modules.practice.dto.response.ExerciseSummaryResponse;
import com.masterenglish.api.modules.practice.dto.response.StartAttemptResult;
import com.masterenglish.api.modules.practice.dto.response.TopicStatisticsResponse;
import com.masterenglish.api.modules.practice.entity.Attempt;
import com.masterenglish.api.modules.practice.entity.AttemptAnswer;
import com.masterenglish.api.modules.practice.entity.AttemptQuestionSnapshot;
import com.masterenglish.api.modules.practice.entity.AttemptSnapshotOption;
import com.masterenglish.api.modules.practice.entity.AttemptSourceTopic;
import com.masterenglish.api.modules.practice.entity.Question;
import com.masterenglish.api.modules.practice.entity.QuestionOption;
import com.masterenglish.api.modules.practice.repository.AttemptAnswerRepository;
import com.masterenglish.api.modules.practice.repository.AttemptQuestionSnapshotRepository;
import com.masterenglish.api.modules.practice.repository.AttemptRepository;
import com.masterenglish.api.modules.practice.repository.AttemptSnapshotOptionRepository;
import com.masterenglish.api.modules.practice.repository.AttemptSourceTopicRepository;
import com.masterenglish.api.modules.practice.repository.QuestionOptionRepository;
import com.masterenglish.api.modules.practice.repository.QuestionRepository;
import com.masterenglish.api.modules.practice.repository.QuestionRepository.PickedQuestion;
import com.masterenglish.api.modules.practice.service.PracticeService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PracticeServiceImpl implements PracticeService {

    static final int DEFAULT_QUESTION_COUNT = 10;
    static final int MAX_QUESTION_COUNT = 50;
    static final String GRAMMAR_TOPIC = "GRAMMAR_TOPIC";
    static final String MIXED_GRAMMAR = "MIXED_GRAMMAR";

    private final IdentityEvents identity;
    private final GrammarEvents grammar;
    private final AttemptRepository attempts;
    private final AttemptSourceTopicRepository sourceTopics;
    private final AttemptQuestionSnapshotRepository snapshots;
    private final AttemptSnapshotOptionRepository snapshotOptions;
    private final AttemptAnswerRepository answers;
    private final QuestionRepository questions;
    private final QuestionOptionRepository questionOptions;
    private final Clock clock;

    public PracticeServiceImpl(
            IdentityEvents identity,
            GrammarEvents grammar,
            AttemptRepository attempts,
            AttemptSourceTopicRepository sourceTopics,
            AttemptQuestionSnapshotRepository snapshots,
            AttemptSnapshotOptionRepository snapshotOptions,
            AttemptAnswerRepository answers,
            QuestionRepository questions,
            QuestionOptionRepository questionOptions,
            Clock clock) {
        this.identity = identity;
        this.grammar = grammar;
        this.attempts = attempts;
        this.sourceTopics = sourceTopics;
        this.snapshots = snapshots;
        this.snapshotOptions = snapshotOptions;
        this.answers = answers;
        this.questions = questions;
        this.questionOptions = questionOptions;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public ExerciseSummaryResponse exercises(UUID topicId) {
        requireVisibleTopic(topicId);
        return new ExerciseSummaryResponse(topicId, questions.countVisibleByTopicId(topicId));
    }

    @Override
    @Transactional
    public StartAttemptResult startTopicAttempt(
            UUID userId, UUID topicId, UUID requestId, Integer questionCount) {
        identity.verifyAndLockUser(userId);
        requireVisibleTopic(topicId);
        int count = questionCount(questionCount);
        String hash = sha256(GRAMMAR_TOPIC + "|" + topicId + "|" + count);
        return start(userId, requestId, hash, GRAMMAR_TOPIC, topicId, List.of(topicId), count);
    }

    @Override
    @Transactional
    public StartAttemptResult startMixedAttempt(
            UUID userId, UUID requestId, List<UUID> topicIds, Integer questionCount) {
        identity.verifyAndLockUser(userId);
        if (topicIds.stream().distinct().count() != topicIds.size()) {
            throw validation("topicIds must not contain duplicates.");
        }
        List<UUID> topics = topicIds.stream().sorted().toList();
        topics.forEach(this::requireVisibleTopic);
        int count = questionCount(questionCount);
        String hash = sha256(MIXED_GRAMMAR + "|" + topics + "|" + count);
        return start(userId, requestId, hash, MIXED_GRAMMAR, null, topics, count);
    }

    @Override
    @Transactional(readOnly = true)
    public AttemptResponse getAttempt(UUID userId, UUID attemptId) {
        return view(userId, attemptId);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AttemptSummaryResponse> listAttempts(
            UUID userId, String status, String sourceType, Pageable pageable) {
        String normalizedStatus = normalizeStatus(status);
        String normalizedSourceType = normalizeSourceType(sourceType);
        Page<Attempt> page =
                attempts.findHistory(
                        userId,
                        normalizedStatus != null,
                        normalizedStatus == null ? "" : normalizedStatus,
                        normalizedSourceType != null,
                        normalizedSourceType == null ? "" : normalizedSourceType,
                        pageable);
        List<UUID> mixedIds =
                page.getContent().stream()
                        .filter(a -> MIXED_GRAMMAR.equals(a.getSourceType()))
                        .map(Attempt::getId)
                        .toList();
        Map<UUID, List<UUID>> mixedTopicsByAttempt =
                mixedIds.isEmpty()
                        ? Map.of()
                        : sourceTopics
                                .findByIdAttemptIdInOrderByIdAttemptIdAscIdTopicIdAsc(mixedIds)
                                .stream()
                                .collect(
                                        Collectors.groupingBy(
                                                t -> t.getId().getAttemptId(),
                                                Collectors.mapping(
                                                        t -> t.getId().getTopicId(),
                                                        Collectors.toList())));
        return PageResponse.from(
                page,
                attempt -> {
                    List<UUID> topicIds =
                            MIXED_GRAMMAR.equals(attempt.getSourceType())
                                    ? mixedTopicsByAttempt.getOrDefault(attempt.getId(), List.of())
                                    : (attempt.getSourceTopicId() == null
                                            ? List.of()
                                            : List.of(attempt.getSourceTopicId()));
                    return new AttemptSummaryResponse(
                            attempt.getId(),
                            attempt.getCategory(),
                            attempt.getSourceType(),
                            topicIds,
                            attempt.getStatus(),
                            attempt.getTotalCount(),
                            attempt.getCorrectCount(),
                            attempt.getScore(),
                            attempt.getStartedAt(),
                            attempt.getSubmittedAt());
                });
    }

    private static String normalizeStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (!Attempt.IN_PROGRESS.equals(normalized) && !Attempt.SUBMITTED.equals(normalized)) {
            throw validation("Unsupported attempt status: " + raw);
        }
        return normalized;
    }

    private static String normalizeSourceType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (!GRAMMAR_TOPIC.equals(normalized) && !MIXED_GRAMMAR.equals(normalized)) {
            throw validation("Unsupported attempt sourceType: " + raw);
        }
        return normalized;
    }

    @Override
    @Transactional
    public AttemptResponse saveAnswer(UUID userId, UUID attemptId, int position, UUID optionId) {
        identity.verifyAndLockUser(userId);
        Attempt attempt = lockOwned(userId, attemptId);
        if (attempt.isSubmitted()) {
            throw new BusinessException(
                    ErrorCode.CONFLICT, "The attempt is already submitted; answers are locked.");
        }
        if (!snapshots.existsById(new AttemptQuestionSnapshot.Id(attemptId, position))) {
            throw notFound("Question not found in this attempt.");
        }
        if (!snapshotOptions.existsByAttemptIdAndQuestionPositionAndId(
                attemptId, position, optionId)) {
            throw validation("The option does not belong to this question.");
        }
        java.time.Instant now = clock.instant();
        answers.findById(new AttemptAnswer.Id(attemptId, position))
                .ifPresentOrElse(
                        answer -> answer.choose(optionId, now),
                        () -> answers.save(new AttemptAnswer(attemptId, position, optionId, now)));
        return view(userId, attemptId);
    }

    @Override
    @Transactional
    public AttemptResponse submit(UUID userId, UUID attemptId) {
        identity.verifyAndLockUser(userId);
        Attempt attempt = lockOwned(userId, attemptId);
        if (!attempt.isSubmitted()) {
            answers.flush();
            int correct = (int) answers.countCorrectAnswers(attemptId);
            attempt.submit(correct, percent(correct, attempt.getTotalCount()), clock.instant());
        }
        return view(userId, attemptId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TopicStatisticsResponse> topicStatistics(UUID userId) {
        return attempts.topicStatistics(userId).stream()
                .map(
                        s ->
                                new TopicStatisticsResponse(
                                        s.getTopicId(),
                                        s.getAttemptCount(),
                                        s.getQuestionCount(),
                                        s.getCorrectCount(),
                                        s.getQuestionCount() == 0
                                                ? null
                                                : percent(
                                                        s.getCorrectCount(), s.getQuestionCount())))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PracticeSummaryView getPracticeSummary(
            UUID userId, Instant weaknessSince, String timezone) {
        long submittedCount = attempts.countByUserIdAndStatus(userId, Attempt.SUBMITTED);
        long inProgressCount = attempts.countByUserIdAndStatus(userId, Attempt.IN_PROGRESS);
        Double avgScore = attempts.averageScoreByUserId(userId);
        Integer avgRounded = avgScore == null ? null : (int) Math.round(avgScore);

        List<AttemptSummaryView> recent =
                attempts
                        .findTop5ByUserIdAndStatusOrderBySubmittedAtDescIdDesc(
                                userId, Attempt.SUBMITTED)
                        .stream()
                        .map(
                                a ->
                                        new AttemptSummaryView(
                                                a.getId(),
                                                a.getSourceType(),
                                                a.getTotalCount(),
                                                a.getCorrectCount(),
                                                a.getScore(),
                                                a.getSubmittedAt()))
                        .toList();

        List<TopicAccuracyView> topicStats =
                attempts.topicStatisticsSince(userId, weaknessSince).stream()
                        .map(
                                s ->
                                        new TopicAccuracyView(
                                                s.getTopicId(),
                                                s.getAttemptCount(),
                                                s.getQuestionCount(),
                                                s.getCorrectCount(),
                                                s.getQuestionCount() == 0
                                                        ? null
                                                        : percent(
                                                                s.getCorrectCount(),
                                                                s.getQuestionCount())))
                        .toList();

        Set<LocalDate> localDays =
                attempts.findSubmittedLocalDays(userId, timezone).stream()
                        .map(LocalDate::parse)
                        .collect(Collectors.toSet());

        return new PracticeSummaryView(
                submittedCount,
                inProgressCount,
                avgRounded,
                recent,
                topicStats,
                Set.copyOf(localDays));
    }

    private StartAttemptResult start(
            UUID userId,
            UUID requestId,
            String hash,
            String sourceType,
            UUID sourceTopicId,
            List<UUID> topics,
            int count) {
        Optional<Attempt> existing = attempts.findByUserIdAndRequestId(userId, requestId);
        if (existing.isPresent()) {
            return replay(existing.get(), hash, userId);
        }
        List<PickedQuestion> picked = questions.pickQuestions(topics, count);
        if (picked.isEmpty()) {
            throw notFound("No published questions for the selected topics.");
        }
        UUID attemptId = UUID.randomUUID();
        int inserted =
                attempts.insertIfAbsent(
                        attemptId,
                        userId,
                        sourceType,
                        sourceTopicId,
                        requestId,
                        hash,
                        picked.size(),
                        clock.instant());
        if (inserted == 0) {
            // A concurrent request with the same request id won the insert; reuse its attempt.
            Attempt winner =
                    attempts.findByUserIdAndRequestId(userId, requestId)
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "Attempt missing after insert."));
            return replay(winner, hash, userId);
        }
        if (MIXED_GRAMMAR.equals(sourceType)) {
            topics.forEach(topic -> sourceTopics.save(new AttemptSourceTopic(attemptId, topic)));
        }
        for (int i = 0; i < picked.size(); i++) {
            PickedQuestion pick = picked.get(i);
            int position = i + 1;
            Question question =
                    questions
                            .findById(pick.getQuestionId())
                            .orElseThrow(() -> new IllegalStateException("Question missing."));
            snapshots.save(
                    new AttemptQuestionSnapshot(
                            attemptId,
                            position,
                            question.getId(),
                            pick.getTopicId(),
                            question.getQuestionTypeCode(),
                            question.getPracticeMode(),
                            question.getPrompt(),
                            question.getExplanation()));
            for (QuestionOption option :
                    questionOptions.findByQuestionIdOrderByPositionAsc(question.getId())) {
                snapshotOptions.save(
                        new AttemptSnapshotOption(
                                UUID.randomUUID(),
                                attemptId,
                                position,
                                option.getPosition(),
                                option.getBody(),
                                option.isCorrect()));
            }
        }
        return new StartAttemptResult(view(userId, attemptId), true);
    }

    private StartAttemptResult replay(Attempt existing, String hash, UUID userId) {
        if (!existing.getRequestHash().equals(hash)) {
            throw new BusinessException(
                    ErrorCode.CONFLICT, "This requestId was already used for a different request.");
        }
        return new StartAttemptResult(view(userId, existing.getId()), false);
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

    /** Builds the learner view. Correct answers and explanations appear only after submission. */
    private AttemptResponse view(UUID userId, UUID attemptId) {
        Attempt attempt =
                attempts.findByIdAndUserId(attemptId, userId)
                        .orElseThrow(() -> notFound("Attempt not found."));
        boolean revealed = attempt.isSubmitted();
        Map<Integer, UUID> selected = new HashMap<>();
        for (AttemptAnswer answer : answers.findByIdAttemptId(attemptId)) {
            selected.put(answer.getId().getQuestionPosition(), answer.getSelectedOptionId());
        }
        Map<Integer, List<AttemptSnapshotOption>> optionsByQuestion =
                snapshotOptions
                        .findByAttemptIdOrderByQuestionPositionAscPositionAsc(attemptId)
                        .stream()
                        .collect(Collectors.groupingBy(AttemptSnapshotOption::getQuestionPosition));
        Map<UUID, Boolean> correctByOption = new HashMap<>();
        optionsByQuestion
                .values()
                .forEach(list -> list.forEach(o -> correctByOption.put(o.getId(), o.isCorrect())));

        List<QuestionResponse> questionViews =
                snapshots.findByIdAttemptIdOrderByIdPositionAsc(attemptId).stream()
                        .map(
                                s ->
                                        question(
                                                s,
                                                selected,
                                                optionsByQuestion,
                                                correctByOption,
                                                revealed))
                        .toList();
        List<UUID> topicIds =
                MIXED_GRAMMAR.equals(attempt.getSourceType())
                        ? sourceTopics.findByIdAttemptIdOrderByIdTopicIdAsc(attemptId).stream()
                                .map(t -> t.getId().getTopicId())
                                .toList()
                        : List.of(attempt.getSourceTopicId());
        return new AttemptResponse(
                attempt.getId(),
                attempt.getSourceType(),
                topicIds,
                attempt.getStatus(),
                attempt.getTotalCount(),
                attempt.getCorrectCount(),
                attempt.getScore(),
                attempt.getStartedAt(),
                attempt.getSubmittedAt(),
                questionViews);
    }

    private static QuestionResponse question(
            AttemptQuestionSnapshot s,
            Map<Integer, UUID> selected,
            Map<Integer, List<AttemptSnapshotOption>> optionsByQuestion,
            Map<UUID, Boolean> correctByOption,
            boolean revealed) {
        int position = s.getId().getPosition();
        UUID selectedOption = selected.get(position);
        List<OptionResponse> options =
                optionsByQuestion.getOrDefault(position, List.of()).stream()
                        .map(
                                o ->
                                        new OptionResponse(
                                                o.getId(),
                                                o.getPosition(),
                                                o.getBody(),
                                                revealed ? o.isCorrect() : null))
                        .toList();
        Boolean correct =
                revealed
                        ? (selectedOption != null
                                && Boolean.TRUE.equals(correctByOption.get(selectedOption)))
                        : null;
        return new QuestionResponse(
                position,
                s.getTopicId(),
                s.getQuestionTypeCode(),
                s.getPracticeMode(),
                s.getPrompt(),
                revealed ? s.getExplanation() : null,
                selectedOption,
                correct,
                options);
    }

    private static int questionCount(Integer requested) {
        int count = requested == null ? DEFAULT_QUESTION_COUNT : requested;
        if (count < 1 || count > MAX_QUESTION_COUNT) {
            throw validation("questionCount must be between 1 and " + MAX_QUESTION_COUNT + ".");
        }
        return count;
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

    private static BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, message);
    }

    private static BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message);
    }
}
