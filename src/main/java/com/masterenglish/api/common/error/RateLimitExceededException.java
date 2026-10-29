package com.masterenglish.api.common.error;

public class RateLimitExceededException extends BusinessException {

    private final long retryAfterSeconds;

    public RateLimitExceededException(long retryAfterSeconds) {
        this(retryAfterSeconds, "Too many login attempts. Please try again later.");
    }

    public RateLimitExceededException(long retryAfterSeconds, String message) {
        super(ErrorCode.RATE_LIMITED, message);
        this.retryAfterSeconds = Math.max(0, retryAfterSeconds);
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
