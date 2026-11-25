package com.masterenglish.api.common.validation;

import com.masterenglish.api.common.time.TimeZones;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public final class IanaTimeZoneValidator
        implements ConstraintValidator<IanaTimeZone, CharSequence> {

    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
        return value == null || TimeZones.isSupportedInput(value.toString());
    }
}
