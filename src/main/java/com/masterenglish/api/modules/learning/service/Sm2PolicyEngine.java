package com.masterenglish.api.modules.learning.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class Sm2PolicyEngine {

    public static final String POLICY_VERSION = "SM2_3BTN_V1";
    public static final BigDecimal EASE_FACTOR_FLOOR = new BigDecimal("1.300");
    public static final BigDecimal INITIAL_EASE_FACTOR = new BigDecimal("2.500");

    public Sm2Result calculate(
            int currentRepetitions,
            int currentIntervalDays,
            BigDecimal currentEaseFactor,
            String rating,
            Instant serverNow) {
        BigDecimal oldEf =
                currentEaseFactor != null
                        ? currentEaseFactor.setScale(3, RoundingMode.HALF_UP)
                        : INITIAL_EASE_FACTOR;

        int q =
                switch (rating.toUpperCase()) {
                    case "GOOD" -> 5;
                    case "HARD" -> 3;
                    case "AGAIN" -> 1;
                    default -> throw new IllegalArgumentException("Unsupported rating: " + rating);
                };

        // deltaEF = 0.1 - (5 - q) * (0.08 + (5 - q) * 0.02)
        double deltaEfDouble = 0.1 - (5 - q) * (0.08 + (5 - q) * 0.02);
        BigDecimal deltaEf = BigDecimal.valueOf(deltaEfDouble).setScale(3, RoundingMode.HALF_UP);

        BigDecimal calculatedEf = oldEf.add(deltaEf).setScale(3, RoundingMode.HALF_UP);
        BigDecimal newEf = calculatedEf.max(EASE_FACTOR_FLOOR);

        int newRepetitions;
        int newIntervalDays;
        Instant newDueAt;

        if ("AGAIN".equalsIgnoreCase(rating)) {
            newRepetitions = 0;
            newIntervalDays = 0;
            newDueAt = serverNow.plus(Duration.ofMinutes(10));
        } else {
            newRepetitions = currentRepetitions + 1;
            if (newRepetitions == 1) {
                newIntervalDays = 1;
            } else if (newRepetitions == 2) {
                newIntervalDays = 6;
            } else {
                // Must use old EF to calculate new interval
                double rawInterval = currentIntervalDays * oldEf.doubleValue();
                long rounded = Math.round(rawInterval);
                newIntervalDays = (int) Math.max(1, rounded);
            }
            newDueAt = serverNow.plus(Duration.ofDays(newIntervalDays));
        }

        return new Sm2Result(newRepetitions, newIntervalDays, newEf, newDueAt);
    }
}
