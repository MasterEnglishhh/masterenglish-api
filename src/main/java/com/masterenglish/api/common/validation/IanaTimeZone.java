package com.masterenglish.api.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Validates a named zone in the JVM tzdb, rather than an arbitrary UTC offset. */
@Documented
@Constraint(validatedBy = IanaTimeZoneValidator.class)
@Target({
    ElementType.FIELD,
    ElementType.METHOD,
    ElementType.PARAMETER,
    ElementType.ANNOTATION_TYPE,
    ElementType.TYPE_USE,
    ElementType.RECORD_COMPONENT
})
@Retention(RetentionPolicy.RUNTIME)
public @interface IanaTimeZone {
    String message() default "Must be a supported IANA time zone.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
