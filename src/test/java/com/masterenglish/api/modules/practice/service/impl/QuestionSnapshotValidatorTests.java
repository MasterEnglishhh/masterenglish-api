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
import com.masterenglish.api.modules.practice.repository.QuestionOptionRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QuestionSnapshotValidatorTests {

    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID topic = UUID.randomUUID();
    private final UUID itemId = UUID.randomUUID();
    private final UUID correctId = UUID.randomUUID();
    private final UUID wrongId = UUID.randomUUID();
    private GrammarEvents grammar;
    private QuestionOptionRepository questionOptions;
    private QuestionSnapshotValidator validator;

    @BeforeEach
    void setUp() {
        grammar = mock(GrammarEvents.class);
        when(grammar.isVisibleTopic(any())).thenReturn(false);
        when(grammar.isVisibleTopic(topic)).thenReturn(true);
        questionOptions = mock(QuestionOptionRepository.class);
        validator = new QuestionSnapshotValidator(grammar, questionOptions);
    }

    @Test
    void acceptsSchemaConformantQuestionAndCompletesTheReply() {
        EventReply<Void> reply = new EventReply<>();
        validator.on(event("QUESTION", valid(), reply));

        assertThat(reply.get("SnapshotValidationRequested")).isNull();
    }

    @Test
    void requiresTypeModeAndDifficultyAtPublication() {
        Map<String, Object> noType = valid();
        noType.remove("question_type_code");
        assertRejected(noType, "question_type_code là bắt buộc khi publish.");

        Map<String, Object> noMode = valid();
        noMode.remove("practice_mode");
        assertRejected(noMode, "practice_mode là bắt buộc khi publish.");

        Map<String, Object> noDifficulty = valid();
        noDifficulty.remove("difficulty");
        assertRejected(noDifficulty, "difficulty là bắt buộc khi publish.");
    }

    @Test
    void nonTextDifficultyIsRejectedWithoutThrowingAnUnexpectedError() {
        Map<String, Object> data = valid();
        data.put("difficulty", 3);
        assertRejected(data, "difficulty là bắt buộc khi publish.");
    }

    @Test
    void leavesOtherContentTypesUnanswered() {
        EventReply<Void> reply = new EventReply<>();
        validator.on(event("GRAMMAR_TOPIC", valid(), reply));

        assertThatThrownBy(() -> reply.get("SnapshotValidationRequested"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsUnknownSnapshotFieldsIncludingTheLegacyQuestionType() {
        Map<String, Object> data = valid();
        data.put("question_type", "SINGLE_CHOICE");
        assertRejected(data, "Unsupported snapshot field: question_type");
    }

    @Test
    void rejectsTheLegacyIsCorrectFlagOnOptions() {
        Map<String, Object> data = valid();
        data.put(
                "options",
                List.of(
                        Map.of(
                                "id",
                                correctId.toString(),
                                "position",
                                1,
                                "body",
                                "goes",
                                "is_correct",
                                true),
                        option(wrongId, 2, "go")));
        assertRejected(data, "Unsupported option field: is_correct");
    }

    @Test
    void answerKeyMustPointAtAnOptionOfThisQuestion() {
        Map<String, Object> data = valid();
        data.put("answer_key", Map.of("correctOptionId", UUID.randomUUID().toString()));
        assertRejected(data, "answer_key.correctOptionId phải là một lựa chọn của câu hỏi.");
    }

    @Test
    void answerKeyRejectsExtraFields() {
        Map<String, Object> data = valid();
        data.put("answer_key", Map.of("correctOptionId", correctId.toString(), "note", "extra"));
        assertRejected(data, "Unsupported answer_key field: note");
    }

    @Test
    void rejectsDuplicateOptionIds() {
        Map<String, Object> data = valid();
        data.put("options", List.of(option(correctId, 1, "goes"), option(correctId, 2, "go")));
        assertRejected(data, "options.id không được trùng nhau.");
    }

    @Test
    void rejectsOptionIdThatBelongsToAnotherQuestion() {
        when(questionOptions.existsByIdAndQuestionIdNot(correctId, itemId)).thenReturn(true);
        assertRejected(valid(), "options.id đã thuộc câu hỏi khác.");
    }

    @Test
    void rejectsDuplicatePositionsAndPositionsOutsideOneToSix() {
        Map<String, Object> duplicate = valid();
        duplicate.put("options", List.of(option(correctId, 1, "goes"), option(wrongId, 1, "go")));
        assertRejected(duplicate, "position phải là số nguyên duy nhất từ 1 đến 6.");

        Map<String, Object> outside = valid();
        outside.put("options", List.of(option(correctId, 1, "goes"), option(wrongId, 7, "go")));
        assertRejected(outside, "position phải là số nguyên duy nhất từ 1 đến 6.");
    }

    @Test
    void rejectsOptionCountOutsideTwoToSix() {
        Map<String, Object> data = valid();
        data.put("options", List.of(option(correctId, 1, "goes")));
        assertRejected(data, "options phải là mảng từ 2 đến 6 phần tử.");
    }

    @Test
    void rejectsNonEmptyFieldsThatPracticeDoesNotStore() {
        Map<String, Object> group = valid();
        group.put("group", "some-group");
        assertRejected(group, "group phải là null trong Practice M04.");

        Map<String, Object> part = valid();
        part.put("part_number", 1);
        assertRejected(part, "part_number phải là null trong Practice M04.");

        Map<String, Object> materials = valid();
        materials.put("materials", List.of(Map.of("id", UUID.randomUUID().toString())));
        assertRejected(materials, "materials phải là mảng rỗng trong M04.");

        Map<String, Object> senses = valid();
        senses.put("word_sense_ids", List.of(UUID.randomUUID().toString()));
        assertRejected(senses, "word_sense_ids phải là mảng rỗng trong M04.");
    }

    @Test
    void rejectsUnknownDifficulty() {
        Map<String, Object> data = valid();
        data.put("difficulty", "BASIC");
        assertRejected(data, "difficulty chỉ nhận EASY, MEDIUM hoặc HARD.");
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
        data.put("category", "INVALID");
        assertRejected(data, "category phải là GRAMMAR, VOCABULARY hoặc TOEIC.");
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
        return new SnapshotValidationRequested(itemId, type, node, reply);
    }

    /** The data object of a QUESTION revision, as ContentPublication passes it to validators. */
    private Map<String, Object> valid() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("category", "GRAMMAR");
        data.put("part_number", null);
        data.put("question_type_code", "SINGLE_CHOICE");
        data.put("practice_mode", "STANDARD");
        data.put("difficulty", "MEDIUM");
        data.put("prompt", "She ___ to school every day.");
        data.put("group", null);
        data.put("options", List.of(option(correctId, 1, "goes"), option(wrongId, 2, "go")));
        data.put("answer_key", Map.of("correctOptionId", correctId.toString()));
        data.put("explanation", "Third person singular takes -s.");
        data.put("materials", List.of());
        data.put("topic_ids", List.of(topic.toString()));
        data.put("word_sense_ids", List.of());
        return data;
    }

    private static Map<String, Object> option(UUID id, int position, String body) {
        return Map.of("id", id.toString(), "position", position, "body", body);
    }
}
