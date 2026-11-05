package com.masterenglish.api.modules.vocabulary.query;

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
        List<WordDetailDto.ExampleDto> examples,
        String audioUrl,
        Boolean isSaved,
        ProgressSummaryDto progress) {

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
