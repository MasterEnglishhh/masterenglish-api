package com.masterenglish.api.modules.notifications.service;

/** Carries a sanitized failure code only; never the recipient, subject or provider message. */
public class ReminderMailException extends RuntimeException {

    private final String failureCode;
    private final boolean permanent;

    public ReminderMailException(String failureCode) {
        this(failureCode, false);
    }

    public ReminderMailException(String failureCode, boolean permanent) {
        super(failureCode);
        this.failureCode = failureCode;
        this.permanent = permanent;
    }

    public static ReminderMailException permanent(String failureCode) {
        return new ReminderMailException(failureCode, true);
    }

    public String getFailureCode() {
        return failureCode;
    }

    public boolean isPermanent() {
        return permanent;
    }
}
