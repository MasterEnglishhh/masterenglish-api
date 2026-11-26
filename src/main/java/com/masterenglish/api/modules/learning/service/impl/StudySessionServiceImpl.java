package com.masterenglish.api.modules.learning.service.impl;

import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.common.events.GrammarEvents;
import com.masterenglish.api.common.events.IdentityEvents;
import com.masterenglish.api.common.events.PracticeEvents;
import com.masterenglish.api.common.events.VocabularyEvents;
import com.masterenglish.api.common.time.TimeZones;
import com.masterenglish.api.modules.learning.dto.request.CloseStudySessionRequest;
import com.masterenglish.api.modules.learning.dto.request.StartStudySessionRequest;
import com.masterenglish.api.modules.learning.dto.request.StudyHeartbeatRequest;
import com.masterenglish.api.modules.learning.dto.response.StudySessionResponse;
import com.masterenglish.api.modules.learning.entity.StudySession;
import com.masterenglish.api.modules.learning.entity.StudySourceType;
import com.masterenglish.api.modules.learning.repository.StudySessionRepository;
import com.masterenglish.api.modules.learning.repository.StudySessionRepository.DailyUserStudySecondsRow;
import com.masterenglish.api.modules.learning.service.StudySessionService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StudySessionServiceImpl implements StudySessionService {

    static final long MAX_HEARTBEAT_MILLIS = 30_000L;

    private final IdentityEvents identity;
    private final VocabularyEvents vocabulary;
    private final GrammarEvents grammar;
    private final PracticeEvents practice;
    private final StudySessionRepository studySessions;
    private final Clock clock;

    public StudySessionServiceImpl(
            IdentityEvents identity,
            VocabularyEvents vocabulary,
            GrammarEvents grammar,
            PracticeEvents practice,
            StudySessionRepository studySessions,
            Clock clock) {
        this.identity = Objects.requireNonNull(identity);
        this.vocabulary = Objects.requireNonNull(vocabulary);
        this.grammar = Objects.requireNonNull(grammar);
        this.practice = Objects.requireNonNull(practice);
        this.studySessions = Objects.requireNonNull(studySessions);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    @Transactional
    public StudySessionResponse startSession(UUID userId, StartStudySessionRequest request) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(request, "request must not be null");
        identity.verifyAndLockUser(userId);

        StudySourceType sourceType = StudySourceType.valueOf(request.sourceType());
        verifySourceAccess(userId, sourceType, request);

        String canonicalTimezone = TimeZones.requireCanonical(request.timezone());
        ZoneId zone = ZoneId.of(canonicalTimezone);
        Instant now = clock.instant();
        LocalDate localDay = now.atZone(zone).toLocalDate();

        studySessions
                .findOpenByUserIdForUpdate(userId)
                .ifPresent(
                        open -> {
                            Instant closeAt =
                                    now.isBefore(open.getLastCreditedAt())
                                            ? open.getLastCreditedAt()
                                            : now;
                            open.close(closeAt);
                            studySessions.saveAndFlush(open);
                        });

        StudySession session =
                StudySession.open(
                        userId,
                        sourceType,
                        request.wordSenseId(),
                        request.lessonId(),
                        request.attemptId(),
                        canonicalTimezone,
                        localDay,
                        UUID.randomUUID(),
                        now);
        StudySession saved = studySessions.saveAndFlush(session);
        long todaySeconds = getDailyStudySecondsInternal(userId, localDay);
        return toResponse(saved, todaySeconds, false);
    }

    @Override
    @Transactional
    public StudySessionResponse heartbeat(
            UUID userId, UUID sessionId, StudyHeartbeatRequest request) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(request, "request must not be null");

        identity.verifyAndLockUser(userId);
        StudySession session =
                studySessions
                        .findByIdAndUserIdForUpdate(sessionId, userId)
                        .orElseThrow(() -> notFound("Study session not found."));

        if (session.isClosed()) {
            throw new BusinessException(ErrorCode.CONFLICT, "Study session is already closed.");
        }
        if (!session.getCreditToken().equals(request.creditToken())
                || session.getVersion() != request.version()) {
            throw new BusinessException(
                    ErrorCode.CONFLICT,
                    "Study session credit token or version is out of sync; resync session.");
        }

        Instant now = clock.instant();
        ZoneId zone = ZoneId.of(session.getTimezone());
        LocalDate currentLocalDay = now.atZone(zone).toLocalDate();

        if (!currentLocalDay.isAfter(session.getLocalDay())) {
            Instant effectiveNow =
                    now.isBefore(session.getLastCreditedAt()) ? session.getLastCreditedAt() : now;
            BigDecimal delta =
                    request.active()
                            ? cappedDeltaSeconds(session.getLastCreditedAt(), effectiveNow)
                            : zeroSeconds();
            session.recordHeartbeat(effectiveNow, delta);
            StudySession saved = studySessions.saveAndFlush(session);
            long todaySeconds = getDailyStudySecondsInternal(userId, saved.getLocalDay());
            return toResponse(saved, todaySeconds, false);
        }

        // Midnight crossed in session timezone: credit pre-midnight portion up to midnight,
        // seal the previous day's row, and open a fresh session for currentLocalDay.
        Instant midnight = currentLocalDay.atStartOfDay(zone).toInstant();
        Instant boundary =
                midnight.isBefore(session.getLastCreditedAt())
                        ? session.getLastCreditedAt()
                        : midnight;
        Instant effectiveNow = now.isBefore(boundary) ? boundary : now;

        BigDecimal preDelta = zeroSeconds();
        BigDecimal postDelta = zeroSeconds();
        if (request.active()) {
            BigDecimal maxTotalDelta =
                    cappedDeltaSeconds(session.getLastCreditedAt(), effectiveNow);
            BigDecimal postElapsed = cappedDeltaSeconds(boundary, effectiveNow);
            postDelta = maxTotalDelta.min(postElapsed);
            BigDecimal remaining = maxTotalDelta.subtract(postDelta);
            BigDecimal preElapsed = cappedDeltaSeconds(session.getLastCreditedAt(), boundary);
            preDelta = remaining.min(preElapsed);
        }

        session.recordHeartbeat(boundary, preDelta);
        session.close(boundary);
        studySessions.saveAndFlush(session);

        StudySession continuation =
                StudySession.openContinuation(
                        session, currentLocalDay, UUID.randomUUID(), boundary);
        continuation.recordHeartbeat(effectiveNow, postDelta);
        StudySession savedNext = studySessions.saveAndFlush(continuation);
        long todaySeconds = getDailyStudySecondsInternal(userId, currentLocalDay);
        return toResponse(savedNext, todaySeconds, true);
    }

    @Override
    @Transactional
    public StudySessionResponse closeSession(
            UUID userId, UUID sessionId, CloseStudySessionRequest request) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(request, "request must not be null");

        identity.verifyAndLockUser(userId);
        StudySession session =
                studySessions
                        .findByIdAndUserIdForUpdate(sessionId, userId)
                        .orElseThrow(() -> notFound("Study session not found."));

        if (session.isClosed()) {
            throw new BusinessException(ErrorCode.CONFLICT, "Study session is already closed.");
        }
        if (!session.getCreditToken().equals(request.creditToken())) {
            throw new BusinessException(
                    ErrorCode.CONFLICT, "Study session credit token does not match.");
        }

        Instant now = clock.instant();
        Instant closeAt =
                now.isBefore(session.getLastCreditedAt()) ? session.getLastCreditedAt() : now;
        session.close(closeAt);
        StudySession saved = studySessions.saveAndFlush(session);
        long todaySeconds = getDailyStudySecondsInternal(userId, saved.getLocalDay());
        return toResponse(saved, todaySeconds, false);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Long> sumDailyStudySeconds(Map<UUID, LocalDate> userLocalDays) {
        if (userLocalDays == null || userLocalDays.isEmpty()) {
            return Map.of();
        }
        Set<UUID> userIds = userLocalDays.keySet();
        Set<LocalDate> localDays = new HashSet<>(userLocalDays.values());
        List<DailyUserStudySecondsRow> rows =
                studySessions.sumActiveSecondsByUsersAndDays(userIds, localDays);
        Map<UUID, Long> result = new HashMap<>();
        for (DailyUserStudySecondsRow row : rows) {
            LocalDate expectedDay = userLocalDays.get(row.getUserId());
            if (expectedDay != null && expectedDay.equals(row.getLocalDay())) {
                result.put(row.getUserId(), toWholeSeconds(row.getActiveSeconds()));
            }
        }
        return Map.copyOf(result);
    }

    @Override
    @Transactional(readOnly = true)
    public long getDailyStudySeconds(UUID userId, LocalDate localDay) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(localDay, "localDay must not be null");
        return getDailyStudySecondsInternal(userId, localDay);
    }

    private long getDailyStudySecondsInternal(UUID userId, LocalDate localDay) {
        return toWholeSeconds(studySessions.sumActiveSecondsForUserAndDay(userId, localDay));
    }

    private void verifySourceAccess(
            UUID userId, StudySourceType sourceType, StartStudySessionRequest request) {
        switch (sourceType) {
            case FLASHCARD ->
                    vocabulary
                            .getCardBySenseId(request.wordSenseId())
                            .orElseThrow(() -> notFound("Word sense not found."));
            case LESSON ->
                    grammar.findPublishedLesson(request.lessonId())
                            .orElseThrow(() -> notFound("Grammar lesson not found."));
            case PRACTICE -> {
                if (!practice.existsOwnedAttempt(userId, request.attemptId())) {
                    throw notFound("Practice attempt not found.");
                }
            }
        }
    }

    private static BigDecimal cappedDeltaSeconds(Instant from, Instant to) {
        long millis = Duration.between(from, to).toMillis();
        if (millis <= 0) {
            return zeroSeconds();
        }
        return BigDecimal.valueOf(Math.min(millis, MAX_HEARTBEAT_MILLIS), 3);
    }

    private static BigDecimal zeroSeconds() {
        return BigDecimal.ZERO.setScale(3);
    }

    private static long toWholeSeconds(BigDecimal raw) {
        if (raw == null || raw.signum() <= 0) {
            return 0L;
        }
        return raw.setScale(0, RoundingMode.FLOOR).longValue();
    }

    private static StudySessionResponse toResponse(
            StudySession session, long todayActiveSeconds, boolean rolledOver) {
        return new StudySessionResponse(
                session.getId(),
                session.getSourceType().name(),
                session.getWordSenseId(),
                session.getLessonId(),
                session.getAttemptId(),
                session.getTimezone(),
                session.getLocalDay(),
                session.getCreditToken(),
                session.getStartedAt(),
                session.getLastCreditedAt(),
                session.getClosedAt(),
                session.getActiveSeconds(),
                session.getVersion(),
                todayActiveSeconds,
                (int) Math.floorDiv(todayActiveSeconds, 60L),
                rolledOver);
    }

    private static BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, message);
    }
}
