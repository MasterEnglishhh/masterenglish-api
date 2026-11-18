package com.masterenglish.api.common.events;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
}
