package com.masterenglish.api.modules.learning.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.modules.identity.user.UserLockService;
import com.masterenglish.api.modules.learning.dto.ReviewRequest;
import com.masterenglish.api.modules.learning.dto.ReviewResponse;
import com.masterenglish.api.modules.learning.entity.UserWordProgress;
import com.masterenglish.api.modules.learning.entity.WordReview;
import com.masterenglish.api.modules.learning.repository.UserWordProgressRepository;
import com.masterenglish.api.modules.learning.repository.WordReviewRepository;
import com.masterenglish.api.modules.vocabulary.query.VocabularyQueryService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ReviewServiceImpl implements ReviewService {

    private final UserLockService userLockService;
    private final UserWordProgressRepository progressRepository;
    private final WordReviewRepository reviewRepository;
    private final VocabularyQueryService vocabularyQueryService;
    private final Sm2PolicyEngine sm2PolicyEngine;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public ReviewServiceImpl(
            UserLockService userLockService,
            UserWordProgressRepository progressRepository,
            WordReviewRepository reviewRepository,
            VocabularyQueryService vocabularyQueryService,
            Sm2PolicyEngine sm2PolicyEngine,
            Clock clock,
            ObjectMapper objectMapper) {
        this.userLockService = userLockService;
        this.progressRepository = progressRepository;
        this.reviewRepository = reviewRepository;
        this.vocabularyQueryService = vocabularyQueryService;
        this.sm2PolicyEngine = sm2PolicyEngine;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    @Override
    public ReviewResponse reviewCard(UUID userId, ReviewRequest request) {
        // 1. Verify user exists and lock user
        userLockService.verifyAndLockUser(userId);

        // 2. Compute canonical request hash
        String requestHash = computeRequestHash(userId, request);

        // 3. Idempotency check before calculating SRS or checking version
        Optional<WordReview> existingReviewOpt =
                reviewRepository.findByUserIdAndRequestId(userId, request.reviewRequestId());
        if (existingReviewOpt.isPresent()) {
            WordReview existing = existingReviewOpt.get();
            if (!existing.getRequestHash().equals(requestHash)) {
                throw new BusinessException(
                        ErrorCode.CONFLICT, "Duplicate review request ID with different payload.");
            }
            // Return stored result
            return storedResponse(existing);
        }

        // 4. Verify context against published catalog
        vocabularyQueryService.resolveReviewCard(
                request.wordSenseId(),
                request.sourceWordId(),
                request.sourceRevisionId(),
                request.deckId(),
                request.deckRevisionId());

        // 5. Lock / acquire progress and check expectedProgressVersion
        Optional<UserWordProgress> progressOpt =
                progressRepository.findByIdForUpdate(userId, request.wordSenseId());

        if (progressOpt.isEmpty()) {
            if (request.expectedProgressVersion() != null) {
                throw new BusinessException(
                        ErrorCode.CONFLICT,
                        "Stale progress version: expected "
                                + request.expectedProgressVersion()
                                + " but card has no prior progress.");
            }
        } else {
            UserWordProgress existingProg = progressOpt.get();
            if (request.expectedProgressVersion() == null
                    || !request.expectedProgressVersion().equals(existingProg.getVersion())) {
                throw new BusinessException(
                        ErrorCode.CONFLICT,
                        "Stale progress version: expected "
                                + request.expectedProgressVersion()
                                + " but current version is "
                                + existingProg.getVersion());
            }
        }

        // 6. Calculate policy SM2_3BTN_V1 using server clock
        Instant serverNow = clock.instant();
        if (progressOpt.isPresent() && progressOpt.get().getDueAt().isAfter(serverNow)) {
            throw new BusinessException(
                    ErrorCode.CONFLICT,
                    "Card is not due yet. Reload the queue or preview without submitting a review.");
        }
        int curRep = progressOpt.map(UserWordProgress::getRepetitions).orElse(0);
        int curInterval = progressOpt.map(UserWordProgress::getIntervalDays).orElse(0);
        BigDecimal curEf =
                progressOpt
                        .map(UserWordProgress::getEaseFactor)
                        .orElse(Sm2PolicyEngine.INITIAL_EASE_FACTOR);
        Instant prevDueAt = progressOpt.map(UserWordProgress::getDueAt).orElse(null);

        Sm2Result result =
                sm2PolicyEngine.calculate(curRep, curInterval, curEf, request.rating(), serverNow);

        // 7. Record beforeState and afterState
        String beforeStateJson = createProgressStateJson(curRep, curInterval, curEf, prevDueAt);

        // Update or insert progress
        UserWordProgress progress;
        if (progressOpt.isPresent()) {
            progress = progressOpt.get();
            progress.updateProgress(
                    result.repetitions(),
                    result.intervalDays(),
                    result.easeFactor(),
                    result.dueAt(),
                    serverNow);
            progress = progressRepository.saveAndFlush(progress);
        } else {
            progress =
                    new UserWordProgress(
                            userId,
                            request.wordSenseId(),
                            result.repetitions(),
                            result.intervalDays(),
                            result.easeFactor(),
                            result.dueAt(),
                            serverNow,
                            Sm2PolicyEngine.POLICY_VERSION);
            progress = progressRepository.saveAndFlush(progress);
        }

        // Persist the original response version with the event; retries never consult live
        // progress.
        ObjectNode afterState = objectMapper.createObjectNode();
        afterState.put("repetitions", result.repetitions());
        afterState.put("interval_days", result.intervalDays());
        afterState.put("ease_factor", result.easeFactor());
        afterState.put("due_at", result.dueAt().toString());
        afterState.put("progress_version", progress.getVersion());

        // 8. Record immutable WordReview event
        String timezone = "UTC";
        LocalDate localDay = serverNow.atZone(ZoneId.of(timezone)).toLocalDate();

        WordReview review =
                new WordReview(
                        request.reviewRequestId(),
                        userId,
                        request.wordSenseId(),
                        request.sourceWordId(),
                        request.sourceRevisionId(),
                        request.reviewRequestId(),
                        requestHash,
                        request.rating().toUpperCase(),
                        Sm2PolicyEngine.POLICY_VERSION,
                        beforeStateJson,
                        afterState.toString(),
                        prevDueAt,
                        result.dueAt(),
                        serverNow,
                        timezone,
                        localDay);
        review = reviewRepository.save(review);

        return new ReviewResponse(
                review.getId(),
                request.wordSenseId(),
                review.getRating(),
                result.intervalDays(),
                result.easeFactor(),
                result.repetitions(),
                result.dueAt(),
                progress.getVersion());
    }

    private ReviewResponse storedResponse(WordReview review) {
        try {
            var state = objectMapper.readTree(review.getAfterState());
            if (!state.has("progress_version")) {
                // V6 events did not record their response version. Do not fabricate one from live
                // state.
                throw new BusinessException(
                        ErrorCode.CONFLICT,
                        "This legacy review is saved but cannot be replayed. Reload your progress.");
            }
            return new ReviewResponse(
                    review.getId(),
                    review.getWordSenseId(),
                    review.getRating(),
                    state.path("interval_days").asInt(),
                    state.path("ease_factor").decimalValue(),
                    state.path("repetitions").asInt(),
                    Instant.parse(state.path("due_at").asText()),
                    state.path("progress_version").asLong());
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(
                    ErrorCode.CONFLICT, "Stored review is unavailable. Reload your progress.");
        }
    }

    private String computeRequestHash(UUID userId, ReviewRequest request) {
        String canonical =
                String.format(
                        "%s:%s:%s:%s:%s:%s:%s:%s:%s",
                        userId,
                        request.reviewRequestId(),
                        request.wordSenseId(),
                        request.rating(),
                        request.sourceWordId(),
                        request.sourceRevisionId(),
                        request.deckId() != null ? request.deckId() : "",
                        request.deckRevisionId() != null ? request.deckRevisionId() : "",
                        request.expectedProgressVersion() != null
                                ? request.expectedProgressVersion()
                                : "");
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private String createProgressStateJson(int rep, int interval, BigDecimal ef, Instant dueAt) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("repetitions", rep);
        node.put("interval_days", interval);
        node.put("ease_factor", ef != null ? ef.doubleValue() : 2.5);
        if (dueAt != null) {
            node.put("due_at", dueAt.toString());
        }
        return node.toString();
    }
}
