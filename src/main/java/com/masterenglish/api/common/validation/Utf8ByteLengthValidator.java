package com.masterenglish.api.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.nio.charset.StandardCharsets;

public final class Utf8ByteLengthValidator
        implements ConstraintValidator<Utf8ByteLength, CharSequence> {
    private int min;
    private int max;

    @Override
    public void initialize(Utf8ByteLength constraint) {
        if (constraint.min() < 0 || constraint.max() < constraint.min()) {
            throw new IllegalArgumentException("Invalid UTF-8 byte length bounds.");
        }
        min = constraint.min();
        max = constraint.max();
    }

    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        int bytes = value.toString().getBytes(StandardCharsets.UTF_8).length;
        return bytes >= min && bytes <= max;
    }
}
