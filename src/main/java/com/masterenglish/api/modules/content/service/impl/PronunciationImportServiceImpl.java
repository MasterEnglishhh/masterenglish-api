package com.masterenglish.api.modules.content.service.impl;

import com.masterenglish.api.common.events.IdentityEvents;
import com.masterenglish.api.common.storage.PronunciationStorage;
import com.masterenglish.api.modules.content.repository.ContentRevisionRepository;
import com.masterenglish.api.modules.content.service.ContentAuthoringService;
import com.masterenglish.api.modules.content.service.PronunciationImportService;
import com.masterenglish.api.modules.content.service.impl.PronunciationManifestReader.Manifest;
import com.masterenglish.api.modules.content.service.impl.PronunciationManifestReader.Recording;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Profile({"local", "content-import"})
public class PronunciationImportServiceImpl implements PronunciationImportService {
    private final ObjectProvider<PronunciationStorage> storageProvider;
    private final ContentRevisionRepository repository;
    private final PronunciationManifestReader manifests;
    private final ContentSnapshots snapshots;
    private final IdentityEvents identity;
    private final ContentAuthoringService authoring;

    public PronunciationImportServiceImpl(
            ObjectProvider<PronunciationStorage> storageProvider,
            ContentRevisionRepository repository,
            PronunciationManifestReader manifests,
            ContentSnapshots snapshots,
            IdentityEvents identity,
            ContentAuthoringService authoring) {
        this.storageProvider = storageProvider;
        this.repository = repository;
        this.manifests = manifests;
        this.snapshots = snapshots;
        this.identity = identity;
        this.authoring = authoring;
    }

    @Override
    @Transactional(rollbackFor = IOException.class)
    public int importManifest(Path manifest, UUID actor) throws IOException {
        return importRecordings(
                manifest, actor, entry -> UUID.fromString(entry.sourceRevisionId()), false);
    }

    @Override
    @Transactional(rollbackFor = IOException.class)
    public int importPortableManifest(Path manifest, UUID actor, Map<UUID, UUID> targetRevisions)
            throws IOException {
        return importRecordings(
                manifest, actor, entry -> targetRevisions.get(entry.wordId()), true);
    }

    @Override
    public Map<UUID, String> validateManifest(Path manifest) throws IOException {
        var words = new LinkedHashMap<UUID, String>();
        for (Recording entry : manifests.read(manifest).recordings()) {
            words.put(entry.wordId(), entry.lemma());
        }
        return Map.copyOf(words);
    }

    private int importRecordings(
            Path manifestPath,
            UUID actor,
            Function<Recording, UUID> targetRevision,
            boolean requireEnglish)
            throws IOException {
        identity.verifyContentAdmin(actor);
        Manifest manifest = manifests.read(manifestPath);
        PronunciationStorage storage = mediaStorage();
        UUID sourceId = ensureSource(manifest, actor);
        repository.flush(); // Persist the source before the native inserts reference it.

        int imported = 0;
        for (Recording entry : manifest.recordings()) {
            UUID revisionId = targetRevision.apply(entry);
            verifyTargetRevision(entry, revisionId, requireEnglish);
            if (!reuseBinding(manifest, entry, revisionId, storage.bucket())) {
                storeRecording(manifest, entry, revisionId, sourceId, actor, storage);
                imported++;
            }
        }
        log.debug("Pronunciation import prepared {} audio bindings", imported);
        return imported;
    }

    private PronunciationStorage mediaStorage() {
        PronunciationStorage storage = storageProvider.getIfAvailable();
        if (storage == null) {
            throw new IllegalStateException("Enable R2 media storage before importing.");
        }
        if (storage.bucket().toLowerCase(Locale.ROOT).contains("backup")) {
            throw new IllegalStateException("Use the media bucket, not backup storage.");
        }
        return storage;
    }

    private UUID ensureSource(Manifest manifest, UUID actor) {
        UUID sourceId =
                stableId(
                        "pronunciation-source:"
                                + manifest.voice()
                                + ":"
                                + manifest.modelChecksum());
        if (!authoring.hasSource(sourceId)) {
            authoring.createSource(
                    sourceId,
                    "Piper synthetic pronunciation: " + manifest.voice(),
                    "EXTERNAL",
                    manifest.sourceUrl(),
                    "Piper / Bryce Beattie / LibriVox",
                    manifest.license(),
                    manifest.sourceUrl(),
                    manifest.attribution(),
                    "Generated locally from the model identified in the manifest. "
                            + manifest.attribution(),
                    actor);
        }
        return sourceId;
    }

    private void verifyTargetRevision(Recording entry, UUID revisionId, boolean requireEnglish) {
        if (revisionId == null) throw new IllegalArgumentException("Word revision unavailable.");
        String snapshot =
                repository
                        .snapshot(entry.wordId(), revisionId, "WORD", true)
                        .orElseThrow(
                                () -> new IllegalArgumentException("Word revision unavailable."));
        var word = snapshots.data(snapshot);
        if (!word.path("lemma").asText().equals(entry.lemma())) {
            throw new IllegalArgumentException("Audio text differs from word snapshot.");
        }
        if (requireEnglish && !word.path("language_code").asText().equals("en")) {
            throw new IllegalArgumentException("Portable pronunciation requires an English word.");
        }
    }

    private boolean reuseBinding(
            Manifest manifest, Recording entry, UUID revisionId, String bucket) {
        if (!repository.bindingExists(revisionId, manifest.locale())) return false;
        if (!repository.bindingMatches(
                entry.wordId(),
                revisionId,
                manifest.locale(),
                manifest.voice(),
                bucket,
                entry.checksum())) {
            throw new IllegalArgumentException(
                    "Existing pronunciation differs; refusing to overwrite it.");
        }
        return true;
    }

    private void storeRecording(
            Manifest manifest,
            Recording entry,
            UUID revisionId,
            UUID sourceId,
            UUID actor,
            PronunciationStorage storage)
            throws IOException {
        String key =
                "pronunciation/"
                        + manifest.locale()
                        + "/"
                        + manifest.voice()
                        + "/"
                        + entry.checksum()
                        + ".mp3";
        UUID assetId = stableId("pronunciation:" + storage.bucket() + ":" + key);
        // Recheck the file near upload because the manifest's preflight ran before database work.
        PronunciationManifestReader.verifyAudio(entry.file(), entry.size(), entry.checksum());
        storage.put(key, entry.file(), entry.checksum());
        repository.insertAsset(
                assetId,
                storage.bucket(),
                key,
                entry.file().getFileName().toString(),
                entry.size(),
                entry.durationMs(),
                entry.checksum(),
                sourceId,
                actor);
        repository.insertBinding(
                entry.wordId(), revisionId, manifest.locale(), manifest.voice(), assetId, actor);
    }

    private static UUID stableId(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }
}
