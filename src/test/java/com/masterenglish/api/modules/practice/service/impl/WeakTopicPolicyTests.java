package com.masterenglish.api.modules.practice.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WeakTopicPolicyTests {

    @Test
    void needsTwentyGradedQuestions() {
        assertThat(WeakTopicPolicy.isWeak(19, 0)).isFalse();
        assertThat(WeakTopicPolicy.isWeak(20, 0)).isTrue();
    }

    @Test
    void seventyPercentExactlyIsNotWeakAndJustBelowIs() {
        assertThat(WeakTopicPolicy.isWeak(20, 14)).isFalse();
        assertThat(WeakTopicPolicy.isWeak(20, 13)).isTrue();
        // 69.9% against 70.0% on a sample of 1000: integer arithmetic, no rounding.
        assertThat(WeakTopicPolicy.isWeak(1000, 699)).isTrue();
        assertThat(WeakTopicPolicy.isWeak(1000, 700)).isFalse();
    }

    @Test
    void constantsMatchTheDataDictionarySeeds() {
        assertThat(WeakTopicPolicy.MIN_SAMPLE).isEqualTo(20);
        assertThat(WeakTopicPolicy.WINDOW_DAYS).isEqualTo(30);
        assertThat(WeakTopicPolicy.BELOW_PERCENT).isEqualTo(70);
    }
}
