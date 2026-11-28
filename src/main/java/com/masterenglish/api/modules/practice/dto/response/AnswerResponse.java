package com.masterenglish.api.modules.practice.dto.response;

import java.time.Instant;
import java.util.UUID;

/** Result of an accepted autosave: the answer's new version and the attempt's new version. */
public record AnswerResponse(
        UUID snapshotId,
        UUID selectedOptionId,
        long answerVersion,
        Instant answeredAt,
        long attemptVersion) {}
