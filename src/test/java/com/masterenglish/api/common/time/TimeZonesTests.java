package com.masterenglish.api.common.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class TimeZonesTests {

    @Test
    void everyLegacyAliasMapsToCanonicalZoneWithEquivalentRules() {
        TimeZones.legacyAliases()
                .forEach(
                        (alias, canonical) -> {
                            assertThat(TimeZones.canonicalZones()).contains(canonical);
                            assertThat(TimeZones.resolveCanonical(alias)).contains(canonical);
                            assertThat(ZoneId.of(alias).getRules())
                                    .as("ZoneRules parity for %s -> %s", alias, canonical)
                                    .isEqualTo(ZoneId.of(canonical).getRules());
                        });
    }

    @Test
    void canonicalizesAliasesAndRejectsOffsetsAndSystemV() {
        assertThat(TimeZones.resolveCanonical("Asia/Saigon")).contains("Asia/Ho_Chi_Minh");
        assertThat(TimeZones.resolveCanonical("Europe/Kiev")).contains("Europe/Kyiv");
        assertThat(TimeZones.resolveCanonical("Asia/Calcutta")).contains("Asia/Kolkata");
        assertThat(TimeZones.resolveCanonical("US/Eastern")).contains("America/New_York");
        assertThat(TimeZones.resolveCanonical("US/Central")).contains("America/Chicago");
        assertThat(TimeZones.resolveCanonical("US/Mountain")).contains("America/Denver");
        assertThat(TimeZones.resolveCanonical("US/Pacific")).contains("America/Los_Angeles");
        assertThat(TimeZones.resolveCanonical(" Asia/Ho_Chi_Minh ")).contains("Asia/Ho_Chi_Minh");

        for (String rejected :
                new String[] {
                    null,
                    "",
                    " ",
                    "+07:00",
                    "UTC+07:00",
                    "GMT+07:00",
                    "Z",
                    "SystemV/AST4",
                    "SystemV/EST5EDT",
                    "EST5EDT",
                    "CST6CDT",
                    "MST7MDT",
                    "PST8PDT",
                    "Mars/Base"
                }) {
            assertThat(TimeZones.resolveCanonical(rejected)).as("reject %s", rejected).isEmpty();
            assertThat(TimeZones.isSupportedInput(rejected)).as("input %s", rejected).isFalse();
            assertThatThrownBy(() -> TimeZones.requireCanonical(rejected))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
