package com.masterenglish.api.modules.learning.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class Sm2PolicyEngineTests {

    private final Sm2PolicyEngine engine = new Sm2PolicyEngine();
    private final Instant now = Instant.parse("2026-11-05T10:00:00Z");

    @Test
    @DisplayName("New card rated GOOD: rep=1, interval=1 day, EF=2.600, due=now+24h")
    void newCardRatedGood() {
        Sm2Result result = engine.calculate(0, 0, new BigDecimal("2.500"), "GOOD", now);

        assertThat(result.repetitions()).isEqualTo(1);
        assertThat(result.intervalDays()).isEqualTo(1);
        assertThat(result.easeFactor()).isEqualByComparingTo("2.600");
        assertThat(result.dueAt()).isEqualTo(now.plus(Duration.ofDays(1)));
    }

    @Test
    @DisplayName("Card at rep=1 rated GOOD: rep=2, interval=6 days, EF=2.700, due=now+6d")
    void cardAtRep1RatedGood() {
        Sm2Result result = engine.calculate(1, 1, new BigDecimal("2.600"), "GOOD", now);

        assertThat(result.repetitions()).isEqualTo(2);
        assertThat(result.intervalDays()).isEqualTo(6);
        assertThat(result.easeFactor()).isEqualByComparingTo("2.700");
        assertThat(result.dueAt()).isEqualTo(now.plus(Duration.ofDays(6)));
    }

    @Test
    @DisplayName(
            "Card at rep=2 rated GOOD uses EF_old (2.700) to calculate interval: 6 * 2.7 = 16.2 -> 16 days")
    void cardAtRep2RatedGoodCalculatesIntervalUsingOldEaseFactor() {
        Sm2Result result = engine.calculate(2, 6, new BigDecimal("2.700"), "GOOD", now);

        assertThat(result.repetitions()).isEqualTo(3);
        // 6 * 2.700 = 16.2 -> round = 16
        assertThat(result.intervalDays()).isEqualTo(16);
        // EF_new = 2.700 + 0.100 = 2.800
        assertThat(result.easeFactor()).isEqualByComparingTo("2.800");
        assertThat(result.dueAt()).isEqualTo(now.plus(Duration.ofDays(16)));
    }

    @Test
    @DisplayName("Rating HARD decreases EF by 0.140 and advances repetitions")
    void ratingHardDecreasesEaseFactor() {
        Sm2Result result = engine.calculate(1, 1, new BigDecimal("2.500"), "HARD", now);

        assertThat(result.repetitions()).isEqualTo(2);
        assertThat(result.intervalDays()).isEqualTo(6);
        // 2.500 - 0.140 = 2.360
        assertThat(result.easeFactor()).isEqualByComparingTo("2.360");
        assertThat(result.dueAt()).isEqualTo(now.plus(Duration.ofDays(6)));
    }

    @Test
    @DisplayName("Rating AGAIN resets rep=0, interval=0, decreases EF by 0.540, due=now+10min")
    void ratingAgainResetsAndSetsDueTo10Minutes() {
        Sm2Result result = engine.calculate(3, 16, new BigDecimal("2.500"), "AGAIN", now);

        assertThat(result.repetitions()).isEqualTo(0);
        assertThat(result.intervalDays()).isEqualTo(0);
        // 2.500 - 0.540 = 1.960
        assertThat(result.easeFactor()).isEqualByComparingTo("1.960");
        assertThat(result.dueAt()).isEqualTo(now.plus(Duration.ofMinutes(10)));
    }

    @Test
    @DisplayName("Ease factor cannot fall below 1.300 floor")
    void easeFactorEnforcesFloor() {
        Sm2Result result = engine.calculate(1, 1, new BigDecimal("1.400"), "AGAIN", now);

        // 1.400 - 0.540 = 0.860 -> clamped to 1.300
        assertThat(result.easeFactor()).isEqualByComparingTo("1.300");
    }
}
