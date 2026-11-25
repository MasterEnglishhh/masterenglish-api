package com.masterenglish.api.modules.practice.service.impl;

/**
 * Weak-topic rule (SCHEMA section 7, DATA_DICTIONARY system_settings keys {@code
 * stats.weak_min_sample}, {@code stats.weak_window_days}, {@code stats.weak_below_percent}). The
 * constants are the seeds; they move into system_settings when that table is added.
 */
final class WeakTopicPolicy {

    static final int MIN_SAMPLE = 20;
    static final int WINDOW_DAYS = 30;
    static final int BELOW_PERCENT = 70;

    private WeakTopicPolicy() {}

    /**
     * A topic is weak only with at least {@link #MIN_SAMPLE} graded questions in the window and an
     * accuracy strictly below {@link #BELOW_PERCENT}. Integer arithmetic, so 70.0% is not weak.
     */
    static boolean isWeak(long sample, long correct) {
        return sample >= MIN_SAMPLE && correct * 100 < (long) BELOW_PERCENT * sample;
    }
}
