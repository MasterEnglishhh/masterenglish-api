package com.masterenglish.api.modules.notifications.service;

import java.time.Instant;

/** Creates due-flashcard reminders (M10.05); safe to run repeatedly and on several instances. */
public interface ReminderPlanner {

    /**
     * @return the number of notifications created in this run
     */
    int planReviewDueReminders(Instant now);
}
