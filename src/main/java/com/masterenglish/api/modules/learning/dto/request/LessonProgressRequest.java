package com.masterenglish.api.modules.learning.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record LessonProgressRequest(
        @NotNull
                @Pattern(
                        regexp = "^(IN_PROGRESS|COMPLETED)$",
                        message = "State must be IN_PROGRESS or COMPLETED")
                String state) {}
