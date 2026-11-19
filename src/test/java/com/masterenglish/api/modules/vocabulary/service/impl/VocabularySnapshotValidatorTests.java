package com.masterenglish.api.modules.vocabulary.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.common.events.ContentEvents;
import com.masterenglish.api.common.events.ContentEvents.SnapshotValidationRequested;
import com.masterenglish.api.common.events.EventReply;
import com.masterenglish.api.modules.vocabulary.repository.WordSenseExampleRepository;
import com.masterenglish.api.modules.vocabulary.repository.WordSenseRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VocabularySnapshotValidatorTests {

    private static final String REQUEST = "SnapshotValidationRequested";

    private final ObjectMapper mapper = new ObjectMapper();
    private final VocabularySnapshotValidator validator =
            new VocabularySnapshotValidator(
                    mock(ContentEvents.class),
                    mapper,
                    mock(WordSenseRepository.class),
                    mock(WordSenseExampleRepository.class));

    @Test
    void grammarAndOtherTypesAreSkippedAndLeftUnanswered() {
        for (String type : new String[] {"GRAMMAR_TOPIC", "GRAMMAR_LESSON", "QUESTION"}) {
            EventReply<Void> reply = new EventReply<>();
            SnapshotValidationRequested event =
                    new SnapshotValidationRequested(
                            UUID.randomUUID(), type, mapper.createObjectNode(), reply);

            assertThatCode(() -> validator.on(event)).doesNotThrowAnyException();
            assertThatThrownBy(() -> reply.get(REQUEST))
                    .as("reply for %s must stay unanswered", type)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void validWordIsValidatedAndAnswered() {
        EventReply<Void> reply = new EventReply<>();
        SnapshotValidationRequested event =
                new SnapshotValidationRequested(UUID.randomUUID(), "WORD", validWord(), reply);

        validator.on(event);

        assertThat(reply.get(REQUEST)).isNull();
    }

    @Test
    void invalidWordIsRejectedAndNotAnswered() {
        ObjectNode word = validWord();
        word.remove("lemma");
        EventReply<Void> reply = new EventReply<>();
        SnapshotValidationRequested event =
                new SnapshotValidationRequested(UUID.randomUUID(), "WORD", word, reply);

        assertThatThrownBy(() -> validator.on(event))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR))
                .hasMessage("lemma is required.");
        assertThatThrownBy(() -> reply.get(REQUEST)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void invalidDeckIsRejectedAndNotAnswered() {
        ObjectNode deck = mapper.createObjectNode();
        deck.putArray("members");
        EventReply<Void> reply = new EventReply<>();
        SnapshotValidationRequested event =
                new SnapshotValidationRequested(UUID.randomUUID(), "DECK", deck, reply);

        assertThatThrownBy(() -> validator.on(event))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR))
                .hasMessage("title is required.");
        assertThatThrownBy(() -> reply.get(REQUEST)).isInstanceOf(IllegalStateException.class);
    }

    private ObjectNode validWord() {
        ObjectNode word = mapper.createObjectNode();
        word.put("lemma", "run");
        word.put("language_code", "en");
        ObjectNode sense = word.putArray("senses").addObject();
        sense.put("id", UUID.randomUUID().toString());
        sense.put("sense_no", 1);
        sense.put("part_of_speech", "VERB");
        sense.put("cefr_level", "A1");
        sense.put("meaning_vi", "chạy");
        return word;
    }
}
