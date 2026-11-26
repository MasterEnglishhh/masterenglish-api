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
            String search, String contentType, String status, String domain, Pageable pageable) {
        return events.request(
                reply ->
                        new AdminFindContentItems(
                                search, contentType, status, domain, pageable, reply));
    }

    /** Creates a draft when {@code request.itemId()} is null, otherwise edits that item's draft. */
    public ItemView saveGrammarDraft(GrammarDraft request, UUID actorId) {
        return events.request(reply -> new AdminSaveGrammarDraft(request, actorId, reply));
    }

    public ItemView createWordDraft(WordDraft request, UUID actorId) {
        return events.request(reply -> new AdminCreateWordDraft(request, actorId, reply));
    }

    public ItemView createDeckDraft(DeckDraft request, UUID actorId) {
        return events.request(reply -> new AdminCreateDeckDraft(request, actorId, reply));
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

    public record RevisionView(
            UUID revisionId,
            UUID contentItemId,
            String status,
            int versionNo,
            String reviewNote,
            Instant publishedAt,
            Instant updatedAt) {}

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

    /** Snapshot JSON is built by the caller; content only stores it. */
    public record GrammarDraft(
            UUID itemId,
            String contentType,
            String snapshotJson,
            String changeSummary,
            UUID sourceId) {}

    public record AdminSource(
            String title,
            String sourceType,
            String sourceUrl,
            String author,
            String licenseCode,
            String licenseUrl,
            String attribution,
            String permissionNote) {}

    public record AdminFindContentItems(
            String search,
            String contentType,
            String status,
            String domain,
            Pageable pageable,
            EventReply<PageResponse<ItemView>> reply) {}

    public record AdminCreateWordDraft(
            WordDraft request, UUID actorId, EventReply<ItemView> reply) {}

    public record AdminCreateDeckDraft(
            DeckDraft request, UUID actorId, EventReply<ItemView> reply) {}

    public record AdminSaveGrammarDraft(
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
}
