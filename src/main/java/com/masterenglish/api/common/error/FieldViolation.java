package com.masterenglish.api.common.error;

/** Rejected values are deliberately excluded from public errors. */
public record FieldViolation(String field, String message) {}
