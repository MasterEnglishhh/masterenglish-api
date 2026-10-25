package com.masterenglish.api.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.time.ZoneId;
import java.util.Set;

public final class IanaTimeZoneValidator implements ConstraintValidator<IanaTimeZone, CharSequence> {
    private static final Set<String> SUPPORTED_ZONES = Set.copyOf(ZoneId.getAvailableZoneIds());

    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
        return value == null || SUPPORTED_ZONES.contains(value.toString());
    }
}
