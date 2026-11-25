package com.masterenglish.api.common.error;

import java.util.Set;
import org.springframework.http.HttpStatus;

public enum ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    ACCESS_DENIED(HttpStatus.FORBIDDEN),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND),
    CONFLICT(HttpStatus.CONFLICT),
    PAYLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
    OTP_INVALID(HttpStatus.BAD_REQUEST),
    OTP_EXPIRED(HttpStatus.BAD_REQUEST),
    REGISTRATION_EXPIRED(HttpStatus.BAD_REQUEST),
    PASSWORD_REJECTED(HttpStatus.BAD_REQUEST),
    RESET_TOKEN_INVALID(HttpStatus.BAD_REQUEST),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR),
    NO_ELIGIBLE_QUESTIONS(HttpStatus.CONFLICT, "requestedCount", "availableCount"),
    INSUFFICIENT_QUESTIONS(HttpStatus.CONFLICT, "requestedCount", "availableCount"),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT),
    OPEN_ATTEMPT_LIMIT_REACHED(HttpStatus.CONFLICT, "openAttemptCount", "maxOpenAttempts"),
    STALE_ANSWER_VERSION(HttpStatus.CONFLICT, "currentVersion", "currentSelectedOptionId"),
    ATTEMPT_NOT_OPEN(HttpStatus.CONFLICT, "attemptStatus"),
    OPTION_NOT_IN_SNAPSHOT(HttpStatus.BAD_REQUEST);

    private final HttpStatus httpStatus;
    private final Set<String> detailKeys;

    ErrorCode(HttpStatus httpStatus, String... detailKeys) {
        this.httpStatus = httpStatus;
        this.detailKeys = Set.of(detailKeys);
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    /** Fixed allowlist in code. A key not listed here must never be sent to the client. */
    public boolean allowsDetail(String key) {
        return key != null && detailKeys.contains(key);
    }
}
