package com.masterenglish.api.modules.content.pronunciation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.masterenglish.api.common.storage.PronunciationStorage;
import com.masterenglish.api.modules.content.authoring.ContentAuthoringService;
import com.masterenglish.api.modules.content.authoring.PublishedContentReader;
import com.masterenglish.api.modules.identity.user.UserLockService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("local")
public class LocalPronunciationImporter {
    private final org.springframework.beans.factory.ObjectProvider<PronunciationStorage>
            storageProvider;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final UserLockService userLock;
    private final PublishedContentReader reader;
    private final ContentAuthoringService authoring;
    private final jakarta.persistence.EntityManager entityManager;

    public LocalPronunciationImporter(
            org.springframework.beans.factory.ObjectProvider<PronunciationStorage> storageProvider,
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            UserLockService userLock,
            PublishedContentReader reader,
            ContentAuthoringService authoring,
            jakarta.persistence.EntityManager entityManager) {
        this.storageProvider = storageProvider;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.userLock = userLock;
        this.reader = reader;
        this.authoring = authoring;
        this.entityManager = entityManager;
    }

    private static UUID id(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    @Transactional
    public int importManifest(Path manifestPath, UUID actor) throws IOException {
        userLock.verifyContentAdmin(actor);
        var storage = storageProvider.getIfAvailable();
        if (storage == null)
            throw new IllegalStateException("Enable R2 media storage before importing.");
        if (storage.bucket().contains("backup"))
            throw new IllegalStateException("Use the media bucket, not backup storage.");
        Path manifest = manifestPath.toRealPath();
        var root = mapper.readTree(Files.readString(manifest));
        String voice = root.path("voiceId").asText();
        String locale = root.path("locale").asText();
        if (!voice.matches("[a-zA-Z0-9_-]{1,100}") || !locale.matches("[a-z]{2}-[A-Z]{2}"))
            throw new IllegalArgumentException("Invalid voice or locale.");
        if (!root.path("entries").isArray() || root.path("entries").isEmpty())
            throw new IllegalArgumentException("Manifest has no audio entries.");
        UUID sourceId =
                id("pronunciation-source:" + voice + ":" + root.path("modelSha256").asText());
        if (!authoring.hasSource(sourceId)) {
            authoring.createSource(
                    sourceId,
                    "Piper synthetic pronunciation: " + voice,
                    "EXTERNAL",
                    root.path("sourceUrl").asText(),
                    "Piper / Bryce Beattie / LibriVox",
                    root.path("licenseCode").asText(),
                    root.path("sourceUrl").asText(),
                    root.path("attribution").asText(),
                    "Generated locally from the model identified in the manifest. "
                            + root.path("attribution").asText(),
                    actor);
        }
        entityManager.flush(); // Source is created with JPA; JDBC inserts need its FK immediately.
        int imported = 0;
        for (var entry : root.path("entries")) {
            UUID wordId = UUID.fromString(entry.path("wordId").asText());
            UUID revisionId = UUID.fromString(entry.path("wordRevisionId").asText());
            if (jdbc.queryForObject(
                            "SELECT count(*) FROM word_pronunciations WHERE word_revision_id=? AND locale=?",
                            Integer.class,
                            revisionId,
                            locale)
                    > 0) continue;
            String snapshot =
                    reader.snapshot(wordId, revisionId, "WORD", true)
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "Word revision unavailable."));
            var word = mapper.readTree(snapshot);
            if (word.has("data")) word = word.path("data");
            if (!word.path("lemma").asText().equals(entry.path("lemma").asText()))
                throw new IllegalArgumentException("Audio text differs from word snapshot.");
            Path file = manifest.getParent().resolve(entry.path("file").asText()).toRealPath();
            if (!file.startsWith(manifest.getParent())
                    || !file.getFileName().toString().endsWith(".mp3"))
                throw new IllegalArgumentException("Audio path is outside the manifest directory.");
            long size = Files.size(file);
            int duration = entry.path("durationMs").asInt();
            if (size < 128 || size > 20 * 1024 * 1024 || duration < 100 || duration > 30000)
                throw new IllegalArgumentException("Invalid pronunciation file size or duration.");
            byte[] bytes = Files.readAllBytes(file);
            if (!((bytes[0] == 'I' && bytes[1] == 'D' && bytes[2] == '3')
                    || ((bytes[0] & 255) == 255 && (bytes[1] & 224) == 224)))
                throw new IllegalArgumentException("Pronunciation file is not MP3.");
            String checksum;
            try {
                checksum =
                        HexFormat.of()
                                .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException(exception);
            }
            if (!checksum.equals(entry.path("sha256").asText()))
                throw new IllegalArgumentException("Pronunciation checksum mismatch.");
            String key = "pronunciation/" + locale + "/" + voice + "/" + checksum + ".mp3";
            UUID assetId = id("pronunciation:" + storage.bucket() + ":" + key);
            storage.put(key, file, checksum);
            jdbc.update(
                    """
                    INSERT INTO media_assets(id,media_type,purpose,bucket,object_key,original_filename,
                        mime_type,file_size_bytes,duration_ms,checksum_sha256,source_id,uploaded_by,status)
                    VALUES (?,'AUDIO','CONTENT',?,?,?,'audio/mpeg',?,?,?,?,?,'READY') ON CONFLICT(id) DO NOTHING
                    """,
                    assetId,
                    storage.bucket(),
                    key,
                    file.getFileName().toString(),
                    size,
                    duration,
                    checksum,
                    sourceId,
                    actor);
            jdbc.update(
                    """
                    INSERT INTO word_pronunciations(word_id,word_revision_id,locale,voice_id,media_asset_id,created_by)
                    VALUES (?,?,?,?,?,?)
                    """,
                    wordId,
                    revisionId,
                    locale,
                    voice,
                    assetId,
                    actor);
            imported++;
        }
        return imported;
    }
}
