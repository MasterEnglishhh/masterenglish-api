package com.masterenglish.api.modules.practice.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.common.events.ContentEvents.SnapshotValidationRequested;
import com.masterenglish.api.common.events.EventReply;
import com.masterenglish.api.common.events.GrammarEvents;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QuestionSnapshotValidatorTests {

    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID topic = UUID.randomUUID();
    private GrammarEvents grammar;
    private QuestionSnapshotValidator validator;

    @BeforeEach
    void setUp() {
        grammar = mock(GrammarEvents.class);
        when(grammar.isVisibleTopic(any())).thenReturn(false);
        when(grammar.isVisibleTopic(topic)).thenReturn(true);
        validator = new QuestionSnapshotValidator(grammar);
    }

    @Test
    void acceptsWellFormedQuestionAndCompletesTheReply() {
        EventReply<Void> reply = new EventReply<>();
        validator.on(event("QUESTION", valid(), reply));

        assertThat(reply.get("SnapshotValidationRequested")).isNull();
    }

    @Test
    void leavesOtherContentTypesUnanswered() {
        EventReply<Void> reply = new EventReply<>();
        validator.on(event("GRAMMAR_TOPIC", valid(), reply));

        assertThatThrownBy(() -> reply.get("SnapshotValidationRequested"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsUnknownFields() {
        Map<String, Object> data = valid();
        data.put("answer_key", "B");
        assertRejected(data, "Unsupported snapshot field: answer_key");
    }

    @Test
    void requiresExactlyOneCorrectOption() {
        Map<String, Object> none = valid();
        none.put("options", List.of(option("a", false), option("b", false)));
        assertRejected(none, "Mỗi câu hỏi phải có đúng một đáp án đúng.");

        Map<String, Object> two = valid();
        two.put("options", List.of(option("a", true), option("b", true)));
        assertRejected(two, "Mỗi câu hỏi phải có đúng một đáp án đúng.");
    }

    @Test
    void rejectsOptionCountOutsideTwoToSix() {
        Map<String, Object> one = valid();
        one.put("options", List.of(option("a", true)));
        assertRejected(one, "options phải là mảng từ 2 đến 6 phần tử.");
    }

    @Test
    void rejectsHtmlInPrompt() {
        Map<String, Object> data = valid();
        data.put("prompt", "She <b>goes</b> to school.");
        assertRejected(data, "prompt không được chứa thẻ HTML.");
    }

    @Test
    void rejectsTopicThatIsNotVisible() {
        Map<String, Object> data = valid();
        data.put("topic_ids", List.of(UUID.randomUUID().toString()));
        assertRejected(data, "topic_ids phải tham chiếu chủ điểm đã xuất bản.");
    }

    @Test
    void rejectsWrongCategory() {
        Map<String, Object> data = valid();
        data.put("category", "TOEIC");
        assertRejected(data, "category phải là GRAMMAR.");
    }

    private void assertRejected(Map<String, Object> data, String message) {
        assertThatThrownBy(() -> validator.on(event("QUESTION", data, new EventReply<>())))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> {
                            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                            assertThat(ex.getMessage()).isEqualTo(message);
                        });
    }

    private SnapshotValidationRequested event(
            String type, Map<String, Object> data, EventReply<Void> reply) {
        JsonNode node = mapper.valueToTree(data);
        return new SnapshotValidationRequested(UUID.randomUUID(), type, node, reply);
    }

    private Map<String, Object> valid() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("category", "GRAMMAR");
        data.put("prompt", "She ___ to school every day.");
        data.put("explanation", "Third person singular takes -s.");
        data.put("topic_ids", List.of(topic.toString()));
        data.put("options", List.of(option("goes", true), option("go", false)));
        return data;
    }

    private static Map<String, Object> option(String body, boolean correct) {
        return Map.of("body", body, "is_correct", correct);
    }
}
