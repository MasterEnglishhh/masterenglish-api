package com.masterenglish.api.modules.content.admin;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;

public record CreateWordDraftRequest(
        @NotBlank String lemma,
        @NotBlank String partOfSpeech,
        @NotBlank String cefrLevel,
        @NotBlank String meaningVi,
        String definitionEn,
        String ipa,
        List<String> examplesEn,
        List<String> examplesVi,
        UUID sourceId,
        String changeSummary) {}
