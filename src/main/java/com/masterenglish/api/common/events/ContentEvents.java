package com.masterenglish.api.common.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.masterenglish.api.common.api.PageResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

@Component
public class ContentEvents {
    private final ModuleEvents events;

    ContentEvents(ModuleEvents events) {
        this.events = events;
    }

    public Optional<String> snapshot(UUID itemId, UUID revisionId, String type, boolean lock) {
        return events.request(
                reply -> new SnapshotRequested(itemId, revisionId, type, lock, reply));
    }

    public String audioUrl(UUID wordId, UUID revisionId) {
        return events.request(reply -> new AudioUrlRequested(wordId, revisionId, reply));
    }

    public boolean hasSource(UUID sourceId) {
        return events.request(reply -> new SourceExistsRequested(sourceId, reply));
    }

    public void createSource(SourceDefinition source) {
        events.<Void>request(reply -> new SourceCreationRequested(source, reply));
    }

    public Optional<UUID> findPublishedRevisionId(String namespace, String externalKey) {
        return events.request(
                reply -> new PublishedRevisionRequested(namespace, externalKey, reply));
    }

    public UUID createAndPublish(DraftDefinition draft) {
        return events.request(reply -> new SeedPublicationRequested(draft, reply));
    }

    public Map<UUID, String> validateManifest(Path manifest) throws IOException {
        try {
            return events.request(reply -> new ManifestValidationRequested(manifest, reply));
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
    }

    public int importPortableManifest(Path manifest, UUID actor, Map<UUID, UUID> revisions)
            throws IOException {
        try {
            return events.request(
                    reply ->
                            new PronunciationImportRequested(
                                    manifest, actor, Map.copyOf(revisions), reply));
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
    }

    public void validateSnapshot(UUID itemId, String type, JsonNode data) {
        events.<Void>request(
                reply -> new SnapshotValidationRequested(itemId, type, data.deepCopy(), reply));
    }

    public void revisionPublished(RevisionPublished event) {
        events.publish(event);
    }

    public record SnapshotRequested(
            UUID itemId,
            UUID revisionId,
            String type,
            boolean lock,
            EventReply<Optional<String>> reply) {}

    public record AudioUrlRequested(UUID wordId, UUID revisionId, EventReply<String> reply) {}

    public record SourceExistsRequested(UUID sourceId, EventReply<Boolean> reply) {}

    public record SourceCreationRequested(SourceDefinition source, EventReply<Void> reply) {}

    public record PublishedRevisionRequested(
            String namespace, String externalKey, EventReply<Optional<UUID>> reply) {}

    public record SeedPublicationRequested(DraftDefinition draft, EventReply<UUID> reply) {}

    public record ManifestValidationRequested(Path manifest, EventReply<Map<UUID, String>> reply) {}

    public record PronunciationImportRequested(
            Path manifest, UUID actor, Map<UUID, UUID> revisions, EventReply<Integer> reply) {}

    public record SnapshotValidationRequested(
            UUID itemId, String type, JsonNode data, EventReply<Void> reply) {}

    /** Projections consume this event synchronously in the originating publication transaction. */
    public record RevisionPublished(UUID itemId, UUID revisionId, String type, String snapshot) {}

    public record SourceDefinition(
            UUID id,
            String title,
            String type,
            String url,
            String author,
            String licenseCode,
            String licenseUrl,
            String attribution,
            String permissionNote,
            UUID actor) {}

    public record DraftDefinition(
            UUID itemId,
            String type,
            UUID actor,
            String namespace,
            String externalKey,
            String snapshot,
            String changeSummary,
            UUID sourceId) {}

    public PageResponse<ItemView> findContentItems(
            String search, String contentType, String status, Pageable pageable) {
        return findContentItems(null, search, contentType, status, pageable);
    }

    public PageResponse<ItemView> findContentItems(
            String domain, String search, String contentType, String status, Pageable pageable) {
        return events.request(
                reply ->
                        new AdminFindContentItems(
                                domain, search, contentType, status, pageable, reply));
    }

    public ItemView createWordDraft(WordDraft request, UUID actorId) {
        return events.request(reply -> new AdminCreateWordDraft(request, actorId, reply));
    }

    public ItemView createDeckDraft(DeckDraft request, UUID actorId) {
        return events.request(reply -> new AdminCreateDeckDraft(request, actorId, reply));
    }

    public ItemView createGrammarTopicDraft(GrammarDraft request, UUID actorId) {
        return events.request(reply -> new AdminCreateGrammarTopicDraft(request, actorId, reply));
    }

    public ItemView createGrammarLessonDraft(GrammarDraft request, UUID actorId) {
        return events.request(reply -> new AdminCreateGrammarLessonDraft(request, actorId, reply));
    }

    public RevisionView submitRevision(UUID revisionId, UUID actorId) {
        return events.request(reply -> new AdminSubmitRevision(revisionId, actorId, reply));
    }

    public RevisionView approveRevision(UUID revisionId, String reviewNote, UUID actorId) {
        return events.request(
                reply -> new AdminApproveRevision(revisionId, reviewNote, actorId, reply));
    }

    public RevisionView rejectRevision(UUID revisionId, String reviewNote, UUID actorId) {
        return events.request(
                reply -> new AdminRejectRevision(revisionId, reviewNote, actorId, reply));
    }

    public RevisionView publishRevision(UUID revisionId, UUID actorId) {
        return events.request(reply -> new AdminPublishRevision(revisionId, actorId, reply));
    }

    public void archiveContentItem(UUID itemId, String reason, UUID actorId) {
        events.<Void>request(reply -> new AdminArchiveContentItem(itemId, reason, actorId, reply));
    }

    public void unpublishContentItem(UUID itemId, String reason, UUID actorId) {
        events.<Void>request(
                reply -> new AdminUnpublishContentItem(itemId, reason, actorId, reply));
    }

    public PageResponse<SourceView> findSources(String search, Pageable pageable) {
        return events.request(reply -> new AdminFindSources(search, pageable, reply));
    }

    public SourceView createSource(AdminSource request, UUID actorId) {
        return events.request(reply -> new AdminCreateSource(request, actorId, reply));
    }

    public PageResponse<AuditEntry> findAuditLogs(
            String search, String entityType, Pageable pageable) {
        return events.request(reply -> new AdminFindAuditLogs(search, entityType, pageable, reply));
    }

    public void recordAuditLog(
            UUID actorId,
            String action,
            String entityType,
            UUID entityId,
            String metadata,
            String reason) {
        events.<Void>request(
                reply ->
                        new AdminRecordAuditLog(
                                actorId, action, entityType, entityId, metadata, reason, reply));
    }

    public long getStorageReadyBytes() {
        return events.request(reply -> new AdminGetStorageReadyBytes(reply));
    }

    public long getStorageReadyCount() {
        return events.request(reply -> new AdminGetStorageReadyCount(reply));
    }

    public List<AuditEntry> getRecentActivities(int limit) {
        return events.request(reply -> new AdminGetRecentActivities(limit, reply));
    }

    public long countTotalContentItems() {
        return events.request(reply -> new AdminCountTotalContentItems(reply));
    }

    public long countPendingReviewRevisions() {
        return events.request(reply -> new AdminCountPendingReviewRevisions(reply));
    }

    public ItemDetailView getContentItemDetail(UUID itemId) {
        return events.request(reply -> new AdminGetContentItemDetail(itemId, reply));
    }

    public PageResponse<RevisionHistoryView> getRevisionHistory(UUID itemId, Pageable pageable) {
        return events.request(reply -> new AdminGetRevisionHistory(itemId, pageable, reply));
    }

    public RevisionView updateDraftRevision(DraftEdit request, UUID actorId) {
        return events.request(reply -> new AdminUpdateDraftRevision(request, actorId, reply));
    }

    public RevisionView createRevisionFromPublished(RevisionClone request, UUID actorId) {
        return events.request(
                reply -> new AdminCreateRevisionFromPublished(request, actorId, reply));
    }

    public ValidationView validateRevision(UUID revisionId) {
        return events.request(reply -> new AdminValidateRevision(revisionId, reply));
    }

    public SourceView updateSource(UpdateSource request, UUID actorId) {
        return events.request(reply -> new AdminUpdateSource(request, actorId, reply));
    }

    public record ItemView(
            UUID id,
            String title,
            String code,
            String module,
            String type,
            String status,
            int versionNo,
            String summary,
            String author,
            String reviewer,
            Instant updatedAt,
            String level,
            UUID latestRevisionId) {}

    public record ItemDetailView(
            UUID id,
            UUID revisionId,
            String contentType,
            String status,
            int versionNo,
            long version,
            long itemVersion,
            Map<String, Object> data,
            List<UUID> sourceIds,
            String changeSummary,
            UUID createdBy,
            UUID reviewedBy,
            Instant reviewedAt,
            String reviewNote,
            Instant publishedAt,
            Instant createdAt,
            Instant updatedAt) {}

    public record RevisionView(
            UUID revisionId,
            UUID contentItemId,
            String status,
            int versionNo,
            String reviewNote,
            Instant publishedAt,
            Instant updatedAt,
            long version) {
        public RevisionView(
                UUID revisionId,
                UUID contentItemId,
                String status,
                int versionNo,
                String reviewNote,
                Instant publishedAt,
                Instant updatedAt) {
            this(
                    revisionId,
                    contentItemId,
                    status,
                    versionNo,
                    reviewNote,
                    publishedAt,
                    updatedAt,
                    0L);
        }
    }

    public record RevisionHistoryView(
            UUID revisionId,
            UUID contentItemId,
            int versionNo,
            String status,
            long version,
            String changeSummary,
            List<UUID> sourceIds,
            UUID createdBy,
            UUID reviewedBy,
            Instant reviewedAt,
            String reviewNote,
            Instant publishedAt,
            Instant createdAt,
            Instant updatedAt) {}

    public record ValidationView(
            boolean valid, List<com.masterenglish.api.common.error.FieldViolation> errors) {}

    public record DraftEdit(
            UUID revisionId,
            long expectedVersion,
            Map<String, Object> data,
            List<UUID> sourceIds,
            String changeSummary) {}

    public record RevisionClone(
            UUID itemId, UUID baseRevisionId, Long expectedItemVersion, String changeSummary) {}

    public record UpdateSource(
            UUID sourceId,
            String title,
            String sourceType,
            String sourceUrl,
            String author,
            String licenseCode,
            String licenseUrl,
            String attribution,
            String permissionNote,
            String status) {}

    public record SourceView(
            UUID id,
            String title,
            String sourceType,
            String sourceUrl,
            String author,
            String licenseCode,
            String status,
            Instant createdAt) {}

    public record AuditEntry(
            UUID id,
            String actorEmail,
            String action,
            String entityType,
            UUID entityId,
            String details,
            String ipAddress,
            Instant timestamp,
            String severity) {}

    public record WordDraft(
            String lemma,
            String partOfSpeech,
            String cefrLevel,
            String meaningVi,
            String definitionEn,
            String ipa,
            List<String> examplesEn,
            List<String> examplesVi,
            UUID sourceId,
            String changeSummary) {}

    public record DeckDraft(
            String title,
            String description,
            String level,
            List<UUID> wordSenseIds,
            UUID sourceId,
            String changeSummary) {}

    public record AdminSource(
            String title,
            String sourceType,
            String sourceUrl,
            String author,
            String licenseCode,
            String licenseUrl,
            String attribution,
            String permissionNote) {}

    public record GrammarDraft(
            Map<String, Object> data, List<UUID> sourceIds, String changeSummary) {}

    public record AdminFindContentItems(
            String domain,
            String search,
            String contentType,
            String status,
            Pageable pageable,
            EventReply<PageResponse<ItemView>> reply) {}

    public record AdminCreateWordDraft(
            WordDraft request, UUID actorId, EventReply<ItemView> reply) {}

    public record AdminCreateDeckDraft(
            DeckDraft request, UUID actorId, EventReply<ItemView> reply) {}

    public record AdminCreateGrammarTopicDraft(
            GrammarDraft request, UUID actorId, EventReply<ItemView> reply) {}

    public record AdminCreateGrammarLessonDraft(
            GrammarDraft request, UUID actorId, EventReply<ItemView> reply) {}

    public record AdminSubmitRevision(
            UUID revisionId, UUID actorId, EventReply<RevisionView> reply) {}

    public record AdminApproveRevision(
            UUID revisionId, String reviewNote, UUID actorId, EventReply<RevisionView> reply) {}

    public record AdminRejectRevision(
            UUID revisionId, String reviewNote, UUID actorId, EventReply<RevisionView> reply) {}

    public record AdminPublishRevision(
            UUID revisionId, UUID actorId, EventReply<RevisionView> reply) {}

    public record AdminArchiveContentItem(
            UUID itemId, String reason, UUID actorId, EventReply<Void> reply) {}

    public record AdminUnpublishContentItem(
            UUID itemId, String reason, UUID actorId, EventReply<Void> reply) {}

    public record AdminFindSources(
            String search, Pageable pageable, EventReply<PageResponse<SourceView>> reply) {}

    public record AdminCreateSource(
            AdminSource request, UUID actorId, EventReply<SourceView> reply) {}

    public record AdminFindAuditLogs(
            String search,
            String entityType,
            Pageable pageable,
            EventReply<PageResponse<AuditEntry>> reply) {}

    public record AdminRecordAuditLog(
            UUID actorId,
            String action,
            String entityType,
            UUID entityId,
            String metadata,
            String reason,
            EventReply<Void> reply) {}

    public record AdminGetStorageReadyBytes(EventReply<Long> reply) {}

    public record AdminGetStorageReadyCount(EventReply<Long> reply) {}

    public record AdminGetRecentActivities(int limit, EventReply<List<AuditEntry>> reply) {}

    public record AdminCountTotalContentItems(EventReply<Long> reply) {}

    public record AdminCountPendingReviewRevisions(EventReply<Long> reply) {}

    public record AdminGetContentItemDetail(UUID itemId, EventReply<ItemDetailView> reply) {}

    public record AdminGetRevisionHistory(
            UUID itemId, Pageable pageable, EventReply<PageResponse<RevisionHistoryView>> reply) {}

    public record AdminUpdateDraftRevision(
            DraftEdit request, UUID actorId, EventReply<RevisionView> reply) {}

    public record AdminCreateRevisionFromPublished(
            RevisionClone request, UUID actorId, EventReply<RevisionView> reply) {}

    public record AdminValidateRevision(UUID revisionId, EventReply<ValidationView> reply) {}

    public record AdminUpdateSource(
            UpdateSource request, UUID actorId, EventReply<SourceView> reply) {}
}
