package com.masterenglish.api.common.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record WordSenseCardDto(
        UUID deckId,
        UUID deckRevisionId,
        int position,
        UUID wordSenseId,
        UUID wordId,
        UUID wordRevisionId,
        String lemma,
        String ipa,
        String partOfSpeech,
        String cefrLevel,
        String meaningVi,
        String definitionEn,
        List<ExampleDto> examples,
        String audioUrl,
        Boolean isSaved,
        ProgressSummaryDto progress) {

    public WordSenseCardDto {
        examples = List.copyOf(examples);
    }

    public record ExampleDto(UUID id, int position, String exampleEn, String exampleVi) {}

    public record ProgressSummaryDto(
            int repetitions,
            int intervalDays,
            BigDecimal easeFactor,
            Instant dueAt,
            long version) {}

    public WordSenseCardDto withPersonalization(Boolean isSaved, ProgressSummaryDto progress) {
        return new WordSenseCardDto(
                deckId,
                deckRevisionId,
                position,
                wordSenseId,
                wordId,
                wordRevisionId,
                lemma,
                ipa,
                partOfSpeech,
                cefrLevel,
                meaningVi,
                definitionEn,
                examples,
                audioUrl,
                isSaved,
                progress);
    }
}
