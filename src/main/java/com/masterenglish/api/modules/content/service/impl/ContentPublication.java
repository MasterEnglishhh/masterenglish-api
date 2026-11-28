package com.masterenglish.api.modules.content.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.common.events.ContentEvents;
import com.masterenglish.api.common.events.ContentEvents.DeckFirstPublished;
import com.masterenglish.api.common.events.ContentEvents.RevisionPublished;
import com.masterenglish.api.modules.content.entity.ContentItem;
import com.masterenglish.api.modules.content.entity.ContentRevision;
import com.masterenglish.api.modules.content.entity.ContentSource;
import com.masterenglish.api.modules.content.entity.MediaAsset;
import com.masterenglish.api.modules.content.entity.RevisionDependency;
import com.masterenglish.api.modules.content.entity.RevisionMediaRef;
import com.masterenglish.api.modules.content.repository.ContentItemRepository;
import com.masterenglish.api.modules.content.repository.ContentRevisionRepository;
import com.masterenglish.api.modules.content.repository.ContentSourceLinkRepository;
import com.masterenglish.api.modules.content.repository.ContentSourceRepository;
import com.masterenglish.api.modules.content.repository.MediaAssetRepository;
import com.masterenglish.api.modules.content.repository.RevisionDependencyRepository;
import com.masterenglish.api.modules.content.repository.RevisionMediaRefRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class ContentPublication {
    // Types with a snapshot validator registered in another module. Add a type here when its module
    // starts validating, so publication of unvalidated types fails with 400 instead of 500.
    private static final Set<String> VALIDATED_TYPES =
            Set.of(
                    "WORD",
                    "DECK",
                    "GRAMMAR_TOPIC",
                    "GRAMMAR_LESSON",
                    "QUESTION",
                    "QUESTION_GROUP",
                    "PRACTICE_SET");

    private final ContentSnapshots snapshots;
    private final ContentSourceLinkRepository sourceLinks;
    private final ContentSourceRepository sources;
    private final RevisionDependencyRepository dependencies;
    private final ContentRevisionRepository revisions;
    private final RevisionMediaRefRepository mediaRefs;
    private final MediaAssetRepository mediaAssets;
    private final ContentItemRepository items;
    private final ContentEvents contentEvents;
    private final ObjectMapper mapper;

    ContentPublication(
            ContentSnapshots snapshots,
            ContentSourceLinkRepository sourceLinks,
            ContentSourceRepository sources,
            RevisionDependencyRepository dependencies,
            ContentEvents contentEvents,
            ObjectMapper mapper) {
        this(
                snapshots,
                sourceLinks,
                sources,
                dependencies,
                null,
                null,
                null,
                null,
                contentEvents,
                mapper);
    }

    @org.springframework.beans.factory.annotation.Autowired
    ContentPublication(
            ContentSnapshots snapshots,
            ContentSourceLinkRepository sourceLinks,
            ContentSourceRepository sources,
            RevisionDependencyRepository dependencies,
            ContentRevisionRepository revisions,
            RevisionMediaRefRepository mediaRefs,
            MediaAssetRepository mediaAssets,
            ContentItemRepository items,
            ContentEvents contentEvents,
            ObjectMapper mapper) {
        this.snapshots = snapshots;
        this.sourceLinks = sourceLinks;
        this.sources = sources;
        this.dependencies = dependencies;
        this.revisions = revisions;
        this.mediaRefs = mediaRefs;
        this.mediaAssets = mediaAssets;
        this.items = items;
        this.contentEvents = contentEvents;
        this.mapper = mapper;
    }

    @FunctionalInterface
    interface ApprovedChildPublisher {
        void publish(UUID groupId, ContentItem item, ContentRevision revision);
    }

    /** Runs inside the authoring transaction before the revision becomes immutable. */
    void prepare(ContentItem item, ContentRevision revision) {
        prepare(
                item,
                revision,
                item.getId(),
                (groupId, childItem, childRevision) -> {
                    throw new BusinessException(
                            ErrorCode.CONFLICT,
                            "Approved child publication requires the authoring transaction.");
                });
    }

    void prepare(
            ContentItem item,
            ContentRevision revision,
            UUID publisherId,
            ApprovedChildPublisher childPublisher) {
        String type = item.getContentType();
        if (!VALIDATED_TYPES.contains(type)) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "Unsupported content type: " + type);
        }
        if ("GRAMMAR_LESSON".equals(type)) {
            if (hasRevisionMediaRefs(revision.getId())) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "Grammar lesson v1 must not reference media.");
            }
        }
        JsonNode data = snapshots.data(revision.getSnapshot());
        contentEvents.validateSnapshot(item.getId(), type, data);
        captureSources(revision.getId());
        if ("WORD".equals(type)) {
            recordWordMediaRefs(revision.getId(), data);
        }
        if ("DECK".equals(type)) {
            recordDeckDependencies(revision.getId(), data.path("members"));
        }
        if ("GRAMMAR_LESSON".equals(type)) {
            recordLessonDependency(revision.getId(), data);
        }
        if ("QUESTION".equals(type)) {
            recordQuestionMediaRefs(revision.getId(), data);
            recordQuestionDependencies(revision.getId(), data);
        }
        if ("QUESTION_GROUP".equals(type)) {
            recordQuestionGroupMediaRefs(revision.getId(), data);
            recordQuestionGroupDependencies(
                    revision, data, item.getId(), publisherId, childPublisher);
        }
        if ("PRACTICE_SET".equals(type)) {
            recordPracticeSetDependencies(item, revision.getId(), data);
        }
    }

    private void recordWordMediaRefs(UUID revisionId, JsonNode data) {
        if (mediaAssets == null || mediaRefs == null) {
            return;
        }
        if (data.hasNonNull("audio_media_id")) {
            UUID wordAudioId = UUID.fromString(data.path("audio_media_id").asText().strip());
            validateAndSaveWordMediaRef(revisionId, wordAudioId, "STIMULUS");
        }
        if (data.has("senses") && data.path("senses").isArray()) {
            for (JsonNode sense : data.path("senses")) {
                if (sense.hasNonNull("audio_media_id")) {
                    UUID senseAudioId =
                            UUID.fromString(sense.path("audio_media_id").asText().strip());
                    validateAndSaveWordMediaRef(revisionId, senseAudioId, "EXPLANATION");
                }
            }
        }
    }

    private void validateAndSaveWordMediaRef(UUID revisionId, UUID mediaId, String role) {
        validateAndSaveMediaRef(
                revisionId,
                mediaId,
                role,
                "AUDIO",
                "Word audio must have media_type AUDIO: " + mediaId);
    }

    private void validateAndSaveMediaRef(
            UUID revisionId,
            UUID mediaId,
            String role,
            String expectedType,
            String typeMismatchMessage) {
        MediaAsset asset =
                mediaAssets
                        .findByIdForUpdate(mediaId)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.VALIDATION_ERROR,
                                                "Media asset not found: " + mediaId));
        if (!"READY".equals(asset.getStatus())) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "Referenced media asset is not READY: " + mediaId);
        }
        if (!"CONTENT".equals(asset.getPurpose())) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Referenced media asset purpose must be CONTENT: " + mediaId);
        }
        if (expectedType != null && !expectedType.equals(asset.getMediaType())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, typeMismatchMessage);
        }
        if (asset.getArchivedAt() != null || "ARCHIVED".equals(asset.getStatus())) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Archived media asset cannot be linked to new content: " + mediaId);
        }
        if (asset.getDeletionStartedAt() != null) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "Media asset is marked for deletion: " + mediaId);
        }
        mediaRefs.save(new RevisionMediaRef(revisionId, mediaId, role));
    }

    void revisionPublished(
            ContentItem item, ContentRevision revision, boolean firstManualPublication) {
        contentEvents.revisionPublished(
                new RevisionPublished(
                        item.getId(),
                        revision.getId(),
                        item.getContentType(),
                        revision.getSnapshot()));
        if (firstManualPublication
                && "DECK".equals(item.getContentType())
                && item.allowsRevisionChanges()
                && isManualNamespace(item.getImportNamespace())) {
            JsonNode data = snapshots.data(revision.getSnapshot());
            String title = data.path("title").asText("");
            String level = data.hasNonNull("level") ? data.path("level").asText(null) : null;
            contentEvents.deckFirstPublished(
                    new DeckFirstPublished(item.getId(), revision.getId(), title, level));
        }
    }

    private static boolean isManualNamespace(String importNamespace) {
        return importNamespace == null || "admin".equals(importNamespace);
    }

    private void recordDeckDependencies(UUID revisionId, JsonNode members) {
        for (JsonNode member : members) {
            UUID pinnedRevision = UUID.fromString(member.path("pinned_word_revision_id").asText());
            String relationKey = "deck_member:" + member.path("word_sense_id").asText();
            dependencies.save(new RevisionDependency(revisionId, relationKey, pinnedRevision));
        }
    }

    /** A lesson pins the topic revision it was validated against, so the pin stays on record. */
    private void recordLessonDependency(UUID revisionId, JsonNode data) {
        UUID topicId = UUID.fromString(data.path("topic_id").asText());
        UUID pinnedRevision = UUID.fromString(data.path("pinned_topic_revision_id").asText());
        dependencies.save(new RevisionDependency(revisionId, "topic:" + topicId, pinnedRevision));
    }

    private void recordMaterialMediaRefs(UUID revisionId, JsonNode data) {
        if (mediaAssets == null || mediaRefs == null || data == null) {
            return;
        }
        JsonNode materials = data.path("materials");
        if (!materials.isArray()) {
            return;
        }
        for (JsonNode mat : materials) {
            if (mat.hasNonNull("media_id")) {
                UUID mediaId = UUID.fromString(mat.path("media_id").asText().strip());
                String role =
                        mat.hasNonNull("usage_role")
                                ? mat.path("usage_role").asText().strip()
                                : "STIMULUS";
                String expectedType =
                        mat.hasNonNull("material_type")
                                ? mat.path("material_type").asText().strip()
                                : null;
                validateAndSaveMediaRef(
                        revisionId,
                        mediaId,
                        role,
                        expectedType,
                        "Media asset type mismatch: " + mediaId);
            }
        }
    }

    private void recordQuestionMediaRefs(UUID revisionId, JsonNode data) {
        recordMaterialMediaRefs(revisionId, data);
    }

    private void recordQuestionDependencies(UUID revisionId, JsonNode data) {
        if (data == null) {
            return;
        }
        JsonNode topicIdsNode = data.path("topic_ids");
        if (topicIdsNode.isArray()) {
            List<UUID> topicIds = new ArrayList<>();
            for (JsonNode t : topicIdsNode) {
                topicIds.add(UUID.fromString(t.asText().strip()));
            }
            List<UUID> sortedTopicIds = topicIds.stream().distinct().sorted().toList();
            for (UUID topicId : sortedTopicIds) {
                UUID publishedRevId =
                        revisions
                                .findPublishedRevisionForShare(topicId, "GRAMMAR_TOPIC")
                                .orElseThrow(
                                        () ->
                                                new BusinessException(
                                                        ErrorCode.VALIDATION_ERROR,
                                                        "topic_ids phải tham chiếu chủ điểm đã xuất bản."));
                dependencies.save(
                        new RevisionDependency(revisionId, "topic:" + topicId, publishedRevId));
            }
        }
        JsonNode wordSenseIdsNode = data.path("word_sense_ids");
        if (wordSenseIdsNode.isArray() && items != null) {
            List<UUID> senseIds = new ArrayList<>();
            for (JsonNode s : wordSenseIdsNode) {
                senseIds.add(UUID.fromString(s.asText().strip()));
            }
            List<UUID> sortedSenseIds = senseIds.stream().distinct().sorted().toList();
            for (UUID senseId : sortedSenseIds) {
                var owner =
                        items.findPublishedWordBySense("[{\"id\":\"" + senseId + "\"}]")
                                .orElseThrow(
                                        () ->
                                                new BusinessException(
                                                        ErrorCode.VALIDATION_ERROR,
                                                        "word_sense_ids phải tham chiếu nghĩa từ đã xuất bản: "
                                                                + senseId));
                dependencies.save(
                        new RevisionDependency(
                                revisionId, "word_sense:" + senseId, owner.getRevisionId()));
            }
        }
    }

    private void recordQuestionGroupMediaRefs(UUID revisionId, JsonNode data) {
        recordMaterialMediaRefs(revisionId, data);
    }

    private void recordQuestionGroupDependencies(
            ContentRevision revision,
            JsonNode data,
            UUID groupId,
            UUID publisherId,
            ApprovedChildPublisher childPublisher) {
        if (data == null) {
            return;
        }
        UUID revisionId = revision.getId();
        JsonNode children = data.path("children");
        if (!children.isArray()) {
            return;
        }
        List<UUID> childIds = new ArrayList<>();
        for (JsonNode child : children) {
            childIds.add(UUID.fromString(child.path("question_id").asText().strip()));
        }
        for (UUID sortedChildId : childIds.stream().distinct().sorted().toList()) {
            items.findByIdForUpdate(sortedChildId)
                    .orElseThrow(
                            () ->
                                    new BusinessException(
                                            ErrorCode.RESOURCE_NOT_FOUND,
                                            "Child question item not found: " + sortedChildId));
        }

        // Collect resolved revisions in order so we can patch snapshot atomically.
        List<UUID> resolvedChildRevIds = new ArrayList<>();

        for (JsonNode child : children) {
            UUID qId = UUID.fromString(child.path("question_id").asText().strip());
            UUID childRevId;
            if (child.hasNonNull("revision_id")) {
                childRevId = UUID.fromString(child.path("revision_id").asText().strip());
                ContentRevision childRev =
                        revisions
                                .findById(childRevId)
                                .orElseThrow(
                                        () ->
                                                new BusinessException(
                                                        ErrorCode.VALIDATION_ERROR,
                                                        "Child question revision not found: "
                                                                + childRevId));
                ContentItem childItem =
                        items.findById(qId)
                                .orElseThrow(
                                        () ->
                                                new BusinessException(
                                                        ErrorCode.RESOURCE_NOT_FOUND,
                                                        "Child question item not found: " + qId));
                if (!"QUESTION".equals(childItem.getContentType())
                        || !childRev.getContentItemId().equals(qId)) {
                    throw new BusinessException(
                            ErrorCode.VALIDATION_ERROR,
                            "Pinned revision must belong to a Question item: " + qId);
                }
                if (!"APPROVED".equals(childRev.getStatus())
                        && !"PUBLISHED".equals(childRev.getStatus())) {
                    throw new BusinessException(
                            ErrorCode.VALIDATION_ERROR,
                            "Child question revision must be APPROVED or PUBLISHED: " + childRevId);
                }
                if ("APPROVED".equals(childRev.getStatus())) {
                    childPublisher.publish(groupId, childItem, childRev);
                } else if (revisions.snapshot(qId, childRevId, "QUESTION", true).isEmpty()) {
                    throw new BusinessException(
                            ErrorCode.VALIDATION_ERROR,
                            "Pinned child revision must be visible: " + childRevId);
                }
            } else {
                Optional<UUID> publishedRevId =
                        revisions.findPublishedRevisionForShare(qId, "QUESTION");
                if (publishedRevId.isPresent()) {
                    childRevId = publishedRevId.get();
                } else {
                    List<ContentRevision> approvedRevs =
                            revisions.findByContentItemIdOrderByVersionNoDesc(qId).stream()
                                    .filter(r -> "APPROVED".equals(r.getStatus()))
                                    .toList();
                    if (!approvedRevs.isEmpty()) {
                        ContentRevision childRev = approvedRevs.get(0);
                        childRevId = childRev.getId();
                        ContentItem childItem =
                                items.findById(qId)
                                        .orElseThrow(
                                                () ->
                                                        new BusinessException(
                                                                ErrorCode.RESOURCE_NOT_FOUND,
                                                                "Child question item not found: "
                                                                        + qId));
                        childPublisher.publish(groupId, childItem, childRev);
                    } else {
                        throw new BusinessException(
                                ErrorCode.VALIDATION_ERROR,
                                "Câu hỏi con chưa có bản xuất bản hoặc được duyệt: " + qId);
                    }
                }
            }
            resolvedChildRevIds.add(childRevId);
            dependencies.save(new RevisionDependency(revisionId, "group_child:" + qId, childRevId));
        }

        // Patch snapshot: inject resolved revision_id into every child entry so the stored
        // snapshot is self-consistent with the dependency ledger.
        patchGroupSnapshotWithResolvedPins(revision, children, resolvedChildRevIds);
    }

    /**
     * Rewrites the Group revision snapshot so each child entry carries the resolved {@code
     * revision_id}. The revision must still be APPROVED at this point (publication has not yet
     * called {@code revision.publish()}).
     */
    private void patchGroupSnapshotWithResolvedPins(
            ContentRevision revision, JsonNode originalChildren, List<UUID> resolvedRevIds) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode root =
                    (com.fasterxml.jackson.databind.node.ObjectNode)
                            mapper.readTree(revision.getSnapshot());
            com.fasterxml.jackson.databind.node.ObjectNode dataNode;
            if (root.has("data")) {
                dataNode = (com.fasterxml.jackson.databind.node.ObjectNode) root.path("data");
            } else {
                dataNode = root;
            }
            com.fasterxml.jackson.databind.node.ArrayNode patchedChildren =
                    mapper.createArrayNode();
            int i = 0;
            for (JsonNode child : originalChildren) {
                com.fasterxml.jackson.databind.node.ObjectNode patchedChild =
                        (com.fasterxml.jackson.databind.node.ObjectNode) child.deepCopy();
                patchedChild.put("revision_id", resolvedRevIds.get(i).toString());
                patchedChildren.add(patchedChild);
                i++;
            }
            dataNode.set("children", patchedChildren);
            revision.resolveChildPins(root.toString());
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(
                    "Failed to patch Group snapshot with resolved child pins.", ex);
        }
    }

    private void recordPracticeSetDependencies(
            ContentItem setItem, UUID revisionId, JsonNode data) {
        if (data == null) {
            return;
        }
        if (data.hasNonNull("deck_id")) {
            UUID deckId = UUID.fromString(data.path("deck_id").asText().strip());
            UUID pinned =
                    revisions
                            .findPublishedRevisionForShare(deckId, "DECK")
                            .orElseThrow(
                                    () ->
                                            new BusinessException(
                                                    ErrorCode.VALIDATION_ERROR,
                                                    "deck_id phải tham chiếu bộ thẻ đã xuất bản: "
                                                            + deckId));
            dependencies.save(new RevisionDependency(revisionId, "deck:" + deckId, pinned));
        }
        if (data.hasNonNull("topic_id")) {
            UUID topicId = UUID.fromString(data.path("topic_id").asText().strip());
            UUID pinned =
                    revisions
                            .findPublishedRevisionForShare(topicId, "GRAMMAR_TOPIC")
                            .orElseThrow(
                                    () ->
                                            new BusinessException(
                                                    ErrorCode.VALIDATION_ERROR,
                                                    "topic_id phải tham chiếu chủ điểm đã xuất bản: "
                                                            + topicId));
            dependencies.save(new RevisionDependency(revisionId, "topic:" + topicId, pinned));
        }
        if ("FIXED".equals(data.path("selection_mode").asText())) {
            validateFixedSetEntries(revisionId, data.path("entries"));
        }
        JsonNode entries = data.path("entries");
        if (entries.isArray()) {
            for (JsonNode entry : entries) {
                if (entry.hasNonNull("question_id")) {
                    UUID qId = UUID.fromString(entry.path("question_id").asText().strip());
                    UUID pinned =
                            UUID.fromString(entry.path("pinned_revision_id").asText().strip());
                    String pinnedSnapshot =
                            revisions
                                    .snapshot(qId, pinned, "QUESTION", true)
                                    .orElseThrow(
                                            () ->
                                                    new BusinessException(
                                                            ErrorCode.VALIDATION_ERROR,
                                                            "entry must pin a visible published question revision: "
                                                                    + qId));
                    if (pinnedSnapshot.isBlank()) {
                        throw new BusinessException(
                                ErrorCode.VALIDATION_ERROR,
                                "pinned_revision_id must match the visible question revision: "
                                        + qId);
                    }
                    dependencies.save(
                            new RevisionDependency(revisionId, "set_question:" + qId, pinned));
                }
                if (entry.hasNonNull("group_id")) {
                    UUID gId = UUID.fromString(entry.path("group_id").asText().strip());
                    UUID pinned =
                            UUID.fromString(entry.path("pinned_revision_id").asText().strip());
                    String pinnedSnapshot =
                            revisions
                                    .snapshot(gId, pinned, "QUESTION_GROUP", true)
                                    .orElseThrow(
                                            () ->
                                                    new BusinessException(
                                                            ErrorCode.VALIDATION_ERROR,
                                                            "entry must pin a visible published group revision: "
                                                                    + gId));
                    if (pinnedSnapshot.isBlank()) {
                        throw new BusinessException(
                                ErrorCode.VALIDATION_ERROR,
                                "pinned_revision_id must match the visible group revision: " + gId);
                    }
                    dependencies.save(
                            new RevisionDependency(revisionId, "set_group:" + gId, pinned));
                }
            }
        }
    }

    private void validateFixedSetEntries(UUID setRevisionId, JsonNode entries) {
        if (items != null && entries.isArray()) {
            List<UUID> lockIds = new ArrayList<>();
            for (JsonNode entry : entries) {
                if (entry.hasNonNull("question_id")) {
                    lockIds.add(UUID.fromString(entry.path("question_id").asText().strip()));
                } else if (entry.hasNonNull("group_id")) {
                    lockIds.add(UUID.fromString(entry.path("group_id").asText().strip()));
                }
            }
            for (UUID sortedId : lockIds.stream().distinct().sorted().toList()) {
                items.findByIdForUpdate(sortedId);
            }
        }
        String category =
                snapshots
                        .data(revisions.findById(setRevisionId).orElseThrow().getSnapshot())
                        .path("category")
                        .asText();
        int totalQuestions = 0;
        for (JsonNode entry : entries) {
            if (entry.hasNonNull("question_id")) {
                UUID questionId = UUID.fromString(entry.path("question_id").asText().strip());
                UUID pinned = UUID.fromString(entry.path("pinned_revision_id").asText().strip());
                String snapshot =
                        revisions
                                .snapshot(questionId, pinned, "QUESTION", true)
                                .orElseThrow(
                                        () ->
                                                new BusinessException(
                                                        ErrorCode.VALIDATION_ERROR,
                                                        "FIXED entry must pin a visible published question: "
                                                                + questionId));
                if (contentEvents.isGroupedQuestion(questionId)) {
                    throw new BusinessException(
                            ErrorCode.VALIDATION_ERROR,
                            "FIXED entry cannot reference a grouped child question: " + questionId);
                }
                if (!categoryMatchesQuestion(category, snapshots.data(snapshot))) {
                    throw new BusinessException(
                            ErrorCode.VALIDATION_ERROR,
                            "FIXED entry question category does not match set: " + questionId);
                }
                totalQuestions++;
            } else {
                UUID groupId = UUID.fromString(entry.path("group_id").asText().strip());
                UUID pinned = UUID.fromString(entry.path("pinned_revision_id").asText().strip());
                String snapshot =
                        revisions
                                .snapshot(groupId, pinned, "QUESTION_GROUP", true)
                                .orElseThrow(
                                        () ->
                                                new BusinessException(
                                                        ErrorCode.VALIDATION_ERROR,
                                                        "FIXED entry must pin a visible published group: "
                                                                + groupId));
                JsonNode groupData = snapshots.data(snapshot);
                if (!category.equals(groupData.path("category").asText())) {
                    throw new BusinessException(
                            ErrorCode.VALIDATION_ERROR,
                            "FIXED entry group category does not match set: " + groupId);
                }
                JsonNode children = groupData.path("children");
                if (!children.isArray() || children.isEmpty()) {
                    throw new BusinessException(
                            ErrorCode.VALIDATION_ERROR, "Pinned question group has no children.");
                }
                totalQuestions += children.size();
            }
            if (totalQuestions > 100) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "FIXED set cannot exceed 100 pinned questions.");
            }
        }
    }

    private static boolean categoryMatchesQuestion(String setCategory, JsonNode question) {
        return setCategory.equals(question.path("category").asText());
    }

    private void captureSources(UUID revisionId) {
        var links = sourceLinks.findByIdRevisionId(revisionId);
        if (links.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "Published content needs a declared source.");
        }
        for (var link : links) {
            ContentSource source =
                    sources.findByIdForShare(link.getId().getSourceId())
                            .orElseThrow(
                                    () ->
                                            new BusinessException(
                                                    ErrorCode.VALIDATION_ERROR,
                                                    "Source unavailable."));
            if (!source.availableForPublication()) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "Active source and declared permission required.");
            }
            link.captureSourceSnapshot(mapper.valueToTree(source).toString());
        }
    }

    private boolean hasRevisionMediaRefs(UUID revisionId) {
        if (revisions == null) {
            return false;
        }
        return revisions.hasRevisionMediaRefs(revisionId);
    }
}
