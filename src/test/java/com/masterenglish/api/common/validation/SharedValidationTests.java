package com.masterenglish.api.common.validation;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

class SharedValidationTests {
    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll
    static void closeFactory() {
        FACTORY.close();
    }

    @Test
    void utf8BudgetIncludesMultibyteCharactersWithoutTruncating() {
        assertThat(VALIDATOR.validate(new Secret("a".repeat(72)))).isEmpty();
        assertThat(VALIDATOR.validate(new Secret("a".repeat(73)))).hasSize(1);
        assertThat(VALIDATOR.validate(new Secret("ệ".repeat(24)))).isEmpty();
        assertThat(VALIDATOR.validate(new Secret("ệ".repeat(25)))).hasSize(1);
        assertThat(VALIDATOR.validate(new Secret("😀".repeat(18)))).isEmpty();
        assertThat(VALIDATOR.validate(new Secret("😀".repeat(19)))).hasSize(1);
    }

    @Test
    void byteValidatorLeavesNullabilityToTheDto() {
        assertThat(VALIDATOR.validate(new Secret(null))).isEmpty();
        assertThat(VALIDATOR.validate(new RequiredSecret(null))).hasSize(1);
    }

    @Test
    void supportsExplicitLowerByteBounds() {
        assertThat(VALIDATOR.validate(new BoundedValue("é"))).isEmpty();
        assertThat(VALIDATOR.validate(new BoundedValue("a"))).hasSize(1);
    }

    @Test
    void acceptsNamedZonesUsedForLocalDayAndReminderScheduling() {
        assertThat(VALIDATOR.validate(new Zone("Asia/Ho_Chi_Minh"))).isEmpty();
        assertThat(VALIDATOR.validate(new Zone("UTC"))).isEmpty();
        assertThat(VALIDATOR.validate(new Zone(null))).isEmpty();
    }

    @Test
    void rejectsOffsetsTyposAndUnnormalizedZoneNames() {
        for (String value :
                new String[] {"+07:00", "GMT+07:00", "Unknown/City", " Asia/Ho_Chi_Minh", ""}) {
            assertThat(VALIDATOR.validate(new Zone(value))).as(value).hasSize(1);
        }
    }

    record Secret(@Utf8ByteLength(max = 72) String value) {}

    record RequiredSecret(@NotNull @Utf8ByteLength(max = 72) String value) {}

    record BoundedValue(@Utf8ByteLength(min = 2, max = 4) String value) {}

    record Zone(@IanaTimeZone String value) {}
}
