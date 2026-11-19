package com.masterenglish.api.modules.notifications.service;

/** Sends one plain-text reminder email. Tests replace it with an in-memory fake. */
public interface ReminderMailer {

    /**
     * @return the provider message ID when the provider returns one, otherwise null
     * @throws ReminderMailException with a sanitized failure code when sending fails
     */
    String send(String recipient, String subject, String text);
}
