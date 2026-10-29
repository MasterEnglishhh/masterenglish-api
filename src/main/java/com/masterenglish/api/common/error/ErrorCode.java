package com.masterenglish.api.common.error;

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
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus httpStatus;

    ErrorCode(HttpStatus httpStatus) {
        this.httpStatus = httpStatus;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
