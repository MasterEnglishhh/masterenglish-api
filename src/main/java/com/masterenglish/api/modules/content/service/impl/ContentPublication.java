package com.masterenglish.api.modules.content.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.common.events.ContentEvents;
import com.masterenglish.api.common.events.ContentEvents.RevisionPublished;
import com.masterenglish.api.modules.content.entity.ContentItem;
import com.masterenglish.api.modules.content.entity.ContentRevision;
import com.masterenglish.api.modules.content.entity.ContentSource;
import com.masterenglish.api.modules.content.entity.RevisionDependency;
import com.masterenglish.api.modules.content.repository.ContentSourceLinkRepository;
import com.masterenglish.api.modules.content.repository.ContentSourceRepository;
import com.masterenglish.api.modules.content.repository.RevisionDependencyRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class ContentPublication {
    private final ContentSnapshots snapshots;
    private final ContentSourceLinkRepository sourceLinks;
    private final ContentSourceRepository sources;
    private final RevisionDependencyRepository dependencies;
    private final ContentEvents contentEvents;
    private final ObjectMapper mapper;

    ContentPublication(
            ContentSnapshots snapshots,
            ContentSourceLinkRepository sourceLinks,
            ContentSourceRepository sources,
            RevisionDependencyRepository dependencies,
            ContentEvents contentEvents,
            ObjectMapper mapper) {
        this.snapshots = snapshots;
        this.sourceLinks = sourceLinks;
        this.sources = sources;
        this.dependencies = dependencies;
        this.contentEvents = contentEvents;
        this.mapper = mapper;
    }

    /** Runs inside the authoring transaction before the revision becomes immutable. */
    void prepare(ContentItem item, ContentRevision revision) {
        String type = item.getContentType();
        JsonNode data = snapshots.data(revision.getSnapshot());
        contentEvents.validateSnapshot(item.getId(), type, data);
        captureSources(revision.getId());
        if ("DECK".equals(type)) {
            recordDeckDependencies(revision.getId(), data.path("members"));
        }
    }

    void revisionPublished(ContentItem item, ContentRevision revision) {
        contentEvents.revisionPublished(
                new RevisionPublished(
                        item.getId(),
                        revision.getId(),
                        item.getContentType(),
                        revision.getSnapshot()));
    }

    private void recordDeckDependencies(UUID revisionId, JsonNode members) {
        for (JsonNode member : members) {
            UUID pinnedRevision = UUID.fromString(member.path("pinned_word_revision_id").asText());
            String relationKey = "deck_member:" + member.path("word_sense_id").asText();
            dependencies.save(new RevisionDependency(revisionId, relationKey, pinnedRevision));
        }
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
}
