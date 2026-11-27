package com.masterenglish.api.modules.practice.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masterenglish.api.common.events.ContentEvents.RevisionPublished;
import com.masterenglish.api.modules.practice.entity.GroupMaterial;
import com.masterenglish.api.modules.practice.entity.Question;
import com.masterenglish.api.modules.practice.entity.QuestionGroup;
import com.masterenglish.api.modules.practice.repository.GroupMaterialRepository;
import com.masterenglish.api.modules.practice.repository.QuestionGroupRepository;
import com.masterenglish.api.modules.practice.repository.QuestionRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class QuestionGroupProjectionListener {

    private static final String QUESTION_GROUP = QuestionGroupSnapshotValidator.QUESTION_GROUP;

    private final QuestionGroupRepository questionGroups;
    private final GroupMaterialRepository groupMaterials;
    private final QuestionRepository questions;
    private final ObjectMapper mapper;
    private final Clock clock;

    public QuestionGroupProjectionListener(
            QuestionGroupRepository questionGroups,
            GroupMaterialRepository groupMaterials,
            QuestionRepository questions,
            ObjectMapper mapper,
            Clock clock) {
        this.questionGroups = questionGroups;
        this.groupMaterials = groupMaterials;
        this.questions = questions;
        this.mapper = mapper;
        this.clock = clock;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void on(RevisionPublished event) {
        if (!QUESTION_GROUP.equals(event.type())) {
            return;
        }
        JsonNode root = root(event.snapshot());
        JsonNode data = root.has("data") ? root.path("data") : root;

        UUID id = event.itemId();
        UUID revisionId = event.revisionId();
        String category = data.path("category").asText();
        Integer partNumber =
                data.hasNonNull("part_number") ? data.path("part_number").asInt() : null;
        String title = data.path("title").asText();
        String groupType = data.path("group_type").asText();
        var now = clock.instant();

        questionGroups
                .findById(id)
                .ifPresentOrElse(
                        g -> g.replaceWith(revisionId, category, partNumber, title, groupType, now),
                        () ->
                                questionGroups.save(
                                        new QuestionGroup(
                                                id,
                                                revisionId,
                                                category,
                                                partNumber,
                                                title,
                                                groupType,
                                                now)));

        groupMaterials.deleteByGroupId(id);
        groupMaterials.flush();

        if (data.hasNonNull("materials") && data.path("materials").isArray()) {
            List<GroupMaterial> materials = new ArrayList<>();
            for (JsonNode mat : data.path("materials")) {
                UUID matId =
                        mat.hasNonNull("id")
                                ? UUID.fromString(mat.path("id").asText())
                                : UUID.randomUUID();
                int pos = mat.path("position").asInt();
                String docKey =
                        mat.hasNonNull("document_key") ? mat.path("document_key").asText() : null;
                String matType = mat.path("material_type").asText();
                String role =
                        mat.hasNonNull("usage_role") ? mat.path("usage_role").asText() : "STIMULUS";
                UUID mediaId =
                        mat.hasNonNull("media_id")
                                ? UUID.fromString(mat.path("media_id").asText())
                                : null;
                String body = mat.hasNonNull("body") ? mat.path("body").asText() : null;
                materials.add(
                        new GroupMaterial(matId, id, pos, docKey, matType, role, mediaId, body));
            }
            groupMaterials.saveAll(materials);
            groupMaterials.flush();
        }

        Set<UUID> currentChildIds = new HashSet<>();
        if (data.hasNonNull("children") && data.path("children").isArray()) {
            for (JsonNode child : data.path("children")) {
                UUID qId = UUID.fromString(child.path("question_id").asText());
                currentChildIds.add(qId);
                int pos = child.path("position").asInt();
                String blankKey =
                        child.hasNonNull("blank_key") ? child.path("blank_key").asText() : null;
                Question question = questions.findByIdForUpdate(qId).orElseThrow();
                question.assignGroup(id, pos, blankKey);
                questions.save(question);
            }
        }

        List<Question> existingGroupQuestions = questions.findByGroupId(id);
        for (Question q : existingGroupQuestions) {
            if (!currentChildIds.contains(q.getId())) {
                q.retireFromGroup();
                questions.save(q);
            }
        }
        questions.flush();
    }

    private JsonNode root(String snapshot) {
        try {
            return mapper.readTree(snapshot);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                    "Published question group snapshot is not valid JSON.", ex);
        }
    }
}
