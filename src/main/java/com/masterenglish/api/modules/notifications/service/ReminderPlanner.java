package com.masterenglish.api.modules.notifications.service;

import java.time.Instant;

/** Creates scheduled study reminders (M10.05, M10.06); safe across retries and instances. */
public interface ReminderPlanner {

    /**
     * @return the number of REVIEW_DUE notifications created in this run
     */
    int planReviewDueReminders(Instant now);

    /**
     * @return the number of DAILY_GOAL notifications created in this run
     */
    int planDailyGoalReminders(Instant now);
}
