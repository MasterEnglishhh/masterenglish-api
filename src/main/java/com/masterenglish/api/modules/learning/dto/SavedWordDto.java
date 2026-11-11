package com.masterenglish.api.modules.learning.dto;

import java.time.Instant;
import java.util.UUID;

public record SavedWordDto(
        UUID wordSenseId,
        UUID wordId,
        String lemma,
        String ipa,
        String partOfSpeech,
        String cefrLevel,
        String meaningVi,
        String definitionEn,
        Instant savedAt) {}
