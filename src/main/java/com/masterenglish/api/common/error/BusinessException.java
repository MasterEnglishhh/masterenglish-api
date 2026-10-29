package com.masterenglish.api.common.error;

import java.util.Objects;

/** The message must be safe to return to the client. */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = Objects.requireNonNull(errorCode);
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
