package com.masterenglish.api.common.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

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
     * @throws IllegalArgumentException if a key is not allowlisted for the code, or a value does
     *     not match the shape of its key (see {@link #isValidDetail}). This is a programming error
     *     and is never sent to the client.
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
            if (!isValidDetail(entry.getKey(), entry.getValue())) {
                throw new IllegalArgumentException(
                        "Detail '" + entry.getKey() + "' has a value of the wrong shape.");
            }
            copy.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(copy);
    }

    /**
     * Shapes allowed by the practice contract (section 4.2): counts are non-negative integers, the
     * current selected option is the caller's own option id (or null), and status is an enum name.
     */
    private static boolean isValidDetail(String key, Object value) {
        return switch (key) {
            case "currentSelectedOptionId" -> value == null || value instanceof UUID;
            case "attemptStatus" -> value instanceof String text && text.matches("[A-Z_]{1,32}");
            default -> isCount(value);
        };
    }

    private static boolean isCount(Object value) {
        return (value instanceof Integer i && i >= 0) || (value instanceof Long l && l >= 0);
    }
}
