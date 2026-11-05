package com.masterenglish.api.modules.vocabulary.query;

import java.util.List;
import java.util.UUID;

public record WordDetailDto(
        UUID id,
        String lemma,
        String languageCode,
        String ipa,
        String audioUrl,
        List<SenseDetailDto> senses) {

    public record SenseDetailDto(
            UUID id,
            short senseNo,
            String partOfSpeech,
            String cefrLevel,
            String meaningVi,
            String definitionEn,
            List<ExampleDto> examples) {}

    public record ExampleDto(UUID id, short position, String exampleEn, String exampleVi) {}
}
