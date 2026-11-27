package com.masterenglish.api.modules.practice.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masterenglish.api.common.events.ContentEvents.GroupedQuestionCheckRequested;
import com.masterenglish.api.common.events.ContentEvents.RevisionPublished;
import com.masterenglish.api.modules.practice.entity.Question;
import com.masterenglish.api.modules.practice.entity.QuestionOption;
import com.masterenglish.api.modules.practice.entity.QuestionTopic;
import com.masterenglish.api.modules.practice.repository.QuestionOptionRepository;
import com.masterenglish.api.modules.practice.repository.QuestionRepository;
import com.masterenglish.api.modules.practice.repository.QuestionTopicRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Copies a published QUESTION revision into the practice read model, inside the publication. */
@Component
public class QuestionProjectionListener {

    private static final String QUESTION = QuestionSnapshotValidator.QUESTION;

    private final QuestionRepository questions;
    private final QuestionOptionRepository questionOptions;
    private final QuestionTopicRepository questionTopics;
    private final com.masterenglish.api.modules.practice.repository.QuestionWordRepository
            questionWords;
    private final ObjectMapper mapper;
    private final Clock clock;

    public QuestionProjectionListener(
            QuestionRepository questions,
            QuestionOptionRepository questionOptions,
            QuestionTopicRepository questionTopics,
            com.masterenglish.api.modules.practice.repository.QuestionWordRepository questionWords,
            ObjectMapper mapper,
            Clock clock) {
        this.questions = questions;
        this.questionOptions = questionOptions;
        this.questionTopics = questionTopics;
        this.questionWords = questionWords;
        this.mapper = mapper;
        this.clock = clock;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void on(GroupedQuestionCheckRequested event) {
        boolean grouped =
                questions
                        .findByIdForUpdate(event.questionId())
                        .map(Question::isGrouped)
                        .orElse(false);
        event.reply().complete(grouped);
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void on(RevisionPublished event) {
        if (!QUESTION.equals(event.type())) {
            return;
        }
        // Content stores the envelope {schema_version, content_type, data}; the fields live in
        // data.
        JsonNode root = root(event.snapshot());
        JsonNode data = root.has("data") ? root.path("data") : root;
        UUID id = event.itemId();
        UUID revisionId = event.revisionId();
        // Required fields were checked at publication; no defaults here.
        String category = data.hasNonNull("category") ? data.path("category").asText() : "GRAMMAR";
        Integer partNumber =
                data.hasNonNull("part_number") ? data.path("part_number").asInt() : null;
        String type = data.path("question_type_code").asText();
        String mode = data.path("practice_mode").asText();
        String difficulty = data.path("difficulty").asText();
        String prompt = data.path("prompt").asText();
        String explanation = data.path("explanation").asText();
        UUID correctOptionId =
                UUID.fromString(data.path("answer_key").path("correctOptionId").asText());
        var now = clock.instant();

        questions
                .findById(id)
                .ifPresentOrElse(
                        q ->
                                q.replaceWith(
                                        revisionId,
                                        category,
                                        partNumber,
                                        type,
                                        mode,
                                        difficulty,
                                        prompt,
                                        explanation,
                                        now),
                        () ->
                                questions.save(
                                        new Question(
                                                id,
                                                revisionId,
                                                category,
                                                partNumber,
                                                type,
                                                mode,
                                                difficulty,
                                                prompt,
                                                explanation,
                                                now)));

        // Delete before inserting: the unique (question, position) and primary keys would clash.
        questionOptions.deleteByQuestionId(id);
        questionOptions.flush();
        // Option ids come from the revision, so re-publishing keeps them stable.
        List<QuestionOption> options = new ArrayList<>();
        for (JsonNode option : data.path("options")) {
            UUID optionId = UUID.fromString(option.path("id").asText());
            options.add(
                    new QuestionOption(
                            optionId,
                            id,
                            option.path("position").asInt(),
                            option.path("body").asText(),
                            optionId.equals(correctOptionId)));
        }
        questionOptions.saveAll(options);

        questionTopics.deleteByIdQuestionId(id);
        questionTopics.flush();
        if (data.hasNonNull("topic_ids") && data.path("topic_ids").isArray()) {
            List<QuestionTopic> topics = new ArrayList<>();
            for (JsonNode topic : data.path("topic_ids")) {
                topics.add(new QuestionTopic(id, UUID.fromString(topic.asText())));
            }
            questionTopics.saveAll(topics);
            questionTopics.flush();
        }

        questionWords.deleteByIdQuestionId(id);
        questionWords.flush();
        if (data.hasNonNull("word_sense_ids") && data.path("word_sense_ids").isArray()) {
            List<com.masterenglish.api.modules.practice.entity.QuestionWord> words =
                    new ArrayList<>();
            for (JsonNode ws : data.path("word_sense_ids")) {
                words.add(
                        new com.masterenglish.api.modules.practice.entity.QuestionWord(
                                id, UUID.fromString(ws.asText().strip())));
            }
            questionWords.saveAll(words);
            questionWords.flush();
        }
    }

    private JsonNode root(String snapshot) {
        try {
            return mapper.readTree(snapshot);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Published question snapshot is not valid JSON.", ex);
        }
    }
}
