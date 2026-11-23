package com.masterenglish.api.modules.practice.dto.request;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record AnswerRequest(@NotNull UUID optionId) {}
