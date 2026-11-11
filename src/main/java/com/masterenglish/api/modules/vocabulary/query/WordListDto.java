package com.masterenglish.api.modules.vocabulary.query;

import java.util.List;
import java.util.UUID;

public record WordListDto(
        UUID id,
        String lemma,
        String languageCode,
        String ipa,
        String audioUrl,
        List<SenseSummaryDto> senses) {

    public record SenseSummaryDto(
            UUID id,
            short senseNo,
            String partOfSpeech,
            String cefrLevel,
            String meaningVi,
            String definitionEn) {}
}
