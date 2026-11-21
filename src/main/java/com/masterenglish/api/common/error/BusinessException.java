package com.masterenglish.api.common.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The message must be safe to return to the client. Details are returned as extra ProblemDetail
 * members, and only keys allowlisted on the {@link ErrorCode} are accepted.
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Map<String, Object> details;

    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, Map.of());
    }

    /**
     * @throws IllegalArgumentException if a key is not allowlisted for the code, or a value is not
     *     a non-negative count. This is a programming error and is never sent to the client.
     */
    public BusinessException(ErrorCode errorCode, String message, Map<String, Object> details) {
        super(message);
        this.errorCode = Objects.requireNonNull(errorCode);
        this.details = copyDetails(errorCode, Objects.requireNonNull(details));
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    private static Map<String, Object> copyDetails(ErrorCode code, Map<String, Object> details) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : details.entrySet()) {
            if (!code.allowsDetail(entry.getKey())) {
                throw new IllegalArgumentException(
                        "Detail key '" + entry.getKey() + "' is not allowlisted for " + code);
            }
            if (!isCount(entry.getValue())) {
                throw new IllegalArgumentException(
                        "Detail '" + entry.getKey() + "' must be a non-negative count.");
            }
            copy.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(copy);
    }

    private static boolean isCount(Object value) {
        return (value instanceof Integer i && i >= 0) || (value instanceof Long l && l >= 0);
    }
}
