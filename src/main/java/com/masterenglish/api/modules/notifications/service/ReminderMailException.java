package com.masterenglish.api.modules.notifications.service;

/** Carries a sanitized failure code only; never the recipient, subject or provider message. */
public class ReminderMailException extends RuntimeException {

    private final String failureCode;

    public ReminderMailException(String failureCode) {
        super(failureCode);
        this.failureCode = failureCode;
    }

    public String getFailureCode() {
        return failureCode;
    }
}
