package com.masterenglish.api.modules.learning.dto.request;

import com.masterenglish.api.common.time.TimeZones;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record StartStudySessionRequest(
        @NotBlank String sourceType,
        UUID wordSenseId,
        UUID lessonId,
        UUID attemptId,
        @NotBlank @Size(max = 60) String timezone) {

    @AssertTrue(message = "sourceType must be one of FLASHCARD, LESSON, PRACTICE.")
    public boolean isSourceTypeSupported() {
        return sourceType == null
                || sourceType.isBlank()
                || "FLASHCARD".equals(sourceType)
                || "LESSON".equals(sourceType)
                || "PRACTICE".equals(sourceType);
    }

    @AssertTrue(message = "Must be a supported IANA time zone.")
    public boolean isTimezoneSupported() {
        return timezone == null || timezone.isBlank() || TimeZones.isSupportedInput(timezone);
    }

    @AssertTrue(message = "Exactly one context identifier matching sourceType must be provided.")
    public boolean isSourceContextConsistent() {
        if (sourceType == null) {
            return true;
        }
        return switch (sourceType) {
            case "FLASHCARD" -> wordSenseId != null && lessonId == null && attemptId == null;
            case "LESSON" -> lessonId != null && wordSenseId == null && attemptId == null;
            case "PRACTICE" -> attemptId != null && wordSenseId == null && lessonId == null;
            default -> true;
        };
    }
}
