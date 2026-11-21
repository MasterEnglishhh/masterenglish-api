package com.masterenglish.api.modules.practice.dto.response;

/** {@code created} is false when the request id was already used with the same body. */
public record StartAttemptResult(AttemptResponse attempt, boolean created) {}
