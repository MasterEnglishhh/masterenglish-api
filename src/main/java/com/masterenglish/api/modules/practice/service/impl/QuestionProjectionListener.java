package com.masterenglish.api.modules.practice.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final ObjectMapper mapper;
    private final Clock clock;

    public QuestionProjectionListener(
            QuestionRepository questions,
            QuestionOptionRepository questionOptions,
            QuestionTopicRepository questionTopics,
            ObjectMapper mapper,
            Clock clock) {
        this.questions = questions;
        this.questionOptions = questionOptions;
        this.questionTopics = questionTopics;
        this.mapper = mapper;
        this.clock = clock;
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
        String type = data.path("question_type_code").asText("SINGLE_CHOICE");
        String mode = data.path("practice_mode").asText("STANDARD");
        String difficulty = data.path("difficulty").asText("MEDIUM");
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
        List<QuestionTopic> topics = new ArrayList<>();
        for (JsonNode topic : data.path("topic_ids")) {
            topics.add(new QuestionTopic(id, UUID.fromString(topic.asText())));
        }
        questionTopics.saveAll(topics);
    }

    private JsonNode root(String snapshot) {
        try {
            return mapper.readTree(snapshot);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Published question snapshot is not valid JSON.", ex);
        }
    }
}
