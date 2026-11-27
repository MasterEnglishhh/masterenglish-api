package com.masterenglish.api.modules.practice.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masterenglish.api.common.events.ContentEvents.RevisionPublished;
import com.masterenglish.api.modules.practice.entity.PracticeSet;
import com.masterenglish.api.modules.practice.entity.SetItem;
import com.masterenglish.api.modules.practice.repository.PracticeSetRepository;
import com.masterenglish.api.modules.practice.repository.SetItemRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class PracticeSetProjectionListener {

    private static final String PRACTICE_SET = PracticeSetSnapshotValidator.PRACTICE_SET;

    private final PracticeSetRepository practiceSets;
    private final SetItemRepository setItems;
    private final ObjectMapper mapper;
    private final Clock clock;

    public PracticeSetProjectionListener(
            PracticeSetRepository practiceSets,
            SetItemRepository setItems,
            ObjectMapper mapper,
            Clock clock) {
        this.practiceSets = practiceSets;
        this.setItems = setItems;
        this.mapper = mapper;
        this.clock = clock;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void on(RevisionPublished event) {
        if (!PRACTICE_SET.equals(event.type())) {
            return;
        }
        JsonNode root = root(event.snapshot());
        JsonNode data = root.has("data") ? root.path("data") : root;

        UUID id = event.itemId();
        UUID revisionId = event.revisionId();
        String category = data.path("category").asText();
        String title = data.path("title").asText();
        String selectionMode = data.path("selection_mode").asText();
        String selectionConfig =
                data.hasNonNull("selection_config")
                        ? data.path("selection_config").toString()
                        : "{}";
        UUID deckId =
                data.hasNonNull("deck_id") ? UUID.fromString(data.path("deck_id").asText()) : null;
        UUID topicId =
                data.hasNonNull("topic_id")
                        ? UUID.fromString(data.path("topic_id").asText())
                        : null;
        Integer partNumber =
                data.hasNonNull("part_number") ? data.path("part_number").asInt() : null;
        Integer timeLimit =
                data.hasNonNull("time_limit_seconds")
                        ? data.path("time_limit_seconds").asInt()
                        : null;
        var now = clock.instant();

        practiceSets
                .findById(id)
                .ifPresentOrElse(
                        s ->
                                s.replaceWith(
                                        revisionId,
                                        category,
                                        title,
                                        selectionMode,
                                        selectionConfig,
                                        deckId,
                                        topicId,
                                        partNumber,
                                        timeLimit,
                                        now),
                        () ->
                                practiceSets.save(
                                        new PracticeSet(
                                                id,
                                                revisionId,
                                                category,
                                                title,
                                                selectionMode,
                                                selectionConfig,
                                                deckId,
                                                topicId,
                                                partNumber,
                                                timeLimit,
                                                now)));

        setItems.deleteBySetId(id);
        setItems.flush();

        if ("FIXED".equals(selectionMode)
                && data.hasNonNull("entries")
                && data.path("entries").isArray()) {
            List<SetItem> items = new ArrayList<>();
            for (JsonNode entry : data.path("entries")) {
                int pos = entry.path("position").asInt();
                UUID qId =
                        entry.hasNonNull("question_id")
                                ? UUID.fromString(entry.path("question_id").asText())
                                : null;
                UUID gId =
                        entry.hasNonNull("group_id")
                                ? UUID.fromString(entry.path("group_id").asText())
                                : null;
                items.add(new SetItem(UUID.randomUUID(), id, pos, qId, gId));
            }
            setItems.saveAll(items);
            setItems.flush();
        }
    }

    private JsonNode root(String snapshot) {
        try {
            return mapper.readTree(snapshot);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                    "Published practice set snapshot is not valid JSON.", ex);
        }
    }
}
