package com.masterenglish.api.modules.content.admin;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;

public record CreateDeckDraftRequest(
        @NotBlank String title,
        String description,
        String level,
        List<UUID> wordSenseIds,
        UUID sourceId,
        String changeSummary) {}
