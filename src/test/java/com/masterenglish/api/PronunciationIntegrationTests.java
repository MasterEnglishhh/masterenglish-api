package com.masterenglish.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.masterenglish.api.common.storage.PronunciationStorage;
import com.masterenglish.api.infrastructure.security.UserPrincipal;
import com.masterenglish.api.modules.content.repository.ContentRevisionRepository;
import com.masterenglish.api.modules.content.service.PronunciationImportService;
import com.masterenglish.api.modules.content.service.PronunciationService;
import com.masterenglish.api.modules.identity.entity.Role;
import com.masterenglish.api.modules.identity.entity.User;
import com.masterenglish.api.modules.identity.repository.UserRepository;
import com.masterenglish.api.modules.vocabulary.service.VocabularyQueryService;
import com.masterenglish.api.modules.vocabulary.service.VocabularySeedService;
import com.masterenglish.api.modules.vocabulary.service.impl.VocabularySeedServiceImpl;
import jakarta.persistence.EntityManager;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("content-import")
@Import(TestcontainersConfiguration.class)
@Transactional
class PronunciationIntegrationTests {
    @Autowired WebApplicationContext web;
    @Autowired UserRepository users;
    @Autowired EntityManager em;
    @Autowired VocabularySeedService seed;
    @Autowired VocabularyQueryService vocabulary;
    @Autowired PronunciationService pronunciation;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired ContentRevisionRepository reader;
    @Autowired PronunciationImportService importer;
    @MockitoBean PronunciationStorage storage;
    private MockMvc mvc;
    private User admin;
    private User learner;
    private final UUID wordId = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private UUID revisionId;
    private UUID assetId;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(web).apply(springSecurity()).build();
        admin = account("audio-admin@example.com", (short) 2);
        learner = account("audio-learner@example.com", (short) 1);
        seed.seedVocabularyData(admin.getId());
        revisionId =
                jdbc.queryForObject(
                        "SELECT published_revision_id FROM words WHERE id=?", UUID.class, wordId);
        assetId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO media_assets(id,media_type,purpose,bucket,object_key,original_filename,
                    mime_type,file_size_bytes,duration_ms,checksum_sha256,source_id,uploaded_by,status)
                VALUES (?,'AUDIO','CONTENT','media-fixture','pronunciation/test.mp3','test.mp3',
                    'audio/mpeg',1024,1200,?,?,?,'READY')
                """,
                assetId,
                "a".repeat(64),
                VocabularySeedServiceImpl.DEFAULT_SOURCE_ID,
                admin.getId());
        jdbc.update(
                "INSERT INTO word_pronunciations VALUES (?,?,'en-GB','test-voice',?,?,NOW())",
                wordId,
                revisionId,
                assetId,
                admin.getId());
        when(storage.bucket()).thenReturn("media-fixture");
        when(storage.signGet(anyString()))
                .thenReturn(
                        URI.create(
                                "https://account.r2.cloudflarestorage.com/media-fixture/test.mp3?signature=test"));
    }

    private User account(String email, short role) {
        var user = new User(email, "unused-test-only-hash", "Audio", "Fixture");
        user.addRole(em.find(Role.class, role));
        user.markEmailVerified(Instant.now());
        return users.saveAndFlush(user);
    }

    @Test
    void catalogAndPinnedCardsExposeOnlyTheMatchingRevisionAudio() {
        String url = "/api/v1/vocabulary/pronunciations/" + revisionId;
        assertThat(vocabulary.getWordDetail(wordId).audioUrl()).isEqualTo(url);
        assertThat(vocabulary.getDeckCards(VocabularySeedServiceImpl.DECK_1_ID))
                .filteredOn(card -> card.wordId().equals(wordId))
                .allSatisfy(card -> assertThat(card.audioUrl()).isEqualTo(url));
        assertThat(vocabulary.getDeckCards(VocabularySeedServiceImpl.DECK_1_ID))
                .filteredOn(card -> !card.wordId().equals(wordId))
                .allSatisfy(card -> assertThat(card.audioUrl()).isNull());
    }

    @Test
    void ticketAndRedirectRequireLearnerAndAreNeverCached() throws Exception {
        String path = "/api/v1/vocabulary/pronunciations/" + revisionId;
        mvc.perform(get(path + "/ticket")).andExpect(status().isUnauthorized());
        mvc.perform(
                        get(path + "/ticket")
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                UserPrincipal.create(admin))))
                .andExpect(status().isForbidden());
        mvc.perform(
                        get(path + "/ticket")
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                UserPrincipal.create(learner))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(
                        jsonPath("$.url")
                                .value(
                                        "https://account.r2.cloudflarestorage.com/media-fixture/test.mp3?signature=test"));
        mvc.perform(
                        get(path)
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                UserPrincipal.create(learner))))
                .andExpect(status().isFound())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }

    @Test
    void revokedWordCannotIssuePlaybackLinks() throws Exception {
        jdbc.update(
                "UPDATE content_items SET access_revoked_at=NOW(),access_revoke_reason='test' WHERE id=?",
                wordId);
        mvc.perform(
                        get("/api/v1/vocabulary/pronunciations/" + revisionId + "/ticket")
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                UserPrincipal.create(learner))))
                .andExpect(status().isNotFound());
    }

    @Test
    void unpublishedWordStillAllowsHistoricalPlaybackWhenNotRevoked() throws Exception {
        jdbc.update(
                "UPDATE content_items SET is_archived = true, archived_at = NOW(), access_revoked_at = NULL WHERE id=?",
                wordId);
        mvc.perform(
                        get("/api/v1/vocabulary/pronunciations/" + revisionId + "/ticket")
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                UserPrincipal.create(learner))))
                .andExpect(status().isOk());
    }

    @Test
    void senseAudioWithExplanationRoleIsNotPickedForWordPronunciation() {
        UUID testRevId = UUID.randomUUID();
        UUID wordAudioId = UUID.randomUUID();
        UUID senseAudioId = UUID.randomUUID();

        jdbc.update(
                """
                INSERT INTO media_assets(id,media_type,purpose,bucket,object_key,original_filename,
                    mime_type,file_size_bytes,duration_ms,checksum_sha256,source_id,uploaded_by,status)
                VALUES (?,'AUDIO','CONTENT','media-fixture','pronunciation/word.mp3','word.mp3',
                    'audio/mpeg',1024,1200,?,?,?,'READY'),
                       (?,'AUDIO','CONTENT','media-fixture','pronunciation/sense.mp3','sense.mp3',
                    'audio/mpeg',1024,1200,?,?,?,'READY')
                """,
                wordAudioId,
                "1".repeat(64),
                VocabularySeedServiceImpl.DEFAULT_SOURCE_ID,
                admin.getId(),
                senseAudioId,
                "2".repeat(64),
                VocabularySeedServiceImpl.DEFAULT_SOURCE_ID,
                admin.getId());

        jdbc.update(
                """
                INSERT INTO content_revisions(id, content_item_id, version_no, status, snapshot, created_by, created_at)
                VALUES (?, ?, 99, 'DRAFT', '{}', ?, NOW())
                """,
                testRevId,
                wordId,
                admin.getId());

        jdbc.update(
                "INSERT INTO revision_media_refs(revision_id, media_id, usage_role) VALUES (?, ?, 'STIMULUS'), (?, ?, 'EXPLANATION')",
                testRevId,
                wordAudioId,
                testRevId,
                senseAudioId);

        jdbc.update(
                "UPDATE content_revisions SET status = 'PUBLISHED', reviewed_by = ?, reviewed_at = NOW(), published_at = NOW() WHERE id = ?",
                admin.getId(),
                testRevId);

        var asset = reader.findPlayableAsset(testRevId).orElseThrow();
        assertThat(asset.getKey()).isEqualTo("pronunciation/word.mp3");
    }

    @Test
    void archivedAudioIsHiddenWithoutRemovingVocabulary() {
        jdbc.update(
                "UPDATE media_assets SET status='ARCHIVED',archived_at=NOW() WHERE id=?", assetId);
        assertThat(vocabulary.getWordDetail(wordId).audioUrl()).isNull();
        assertThatThrownBy(() -> pronunciation.playbackLocation(revisionId))
                .isInstanceOf(com.masterenglish.api.common.error.BusinessException.class);
    }

    @Test
    void invalidChecksumNeverUploads(@TempDir Path directory) throws Exception {
        jdbc.update("DELETE FROM word_pronunciations WHERE word_revision_id=?", revisionId);
        Path manifest = prepareManifest(directory, "b".repeat(64));
        assertThatThrownBy(() -> importer.importManifest(manifest, admin.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("checksum");
        org.mockito.Mockito.verify(storage, org.mockito.Mockito.never())
                .put(anyString(), org.mockito.ArgumentMatchers.any(), anyString());
    }

    @Test
    void importRejectsAudioThatChangesAfterManifestValidation(@TempDir Path directory)
            throws Exception {
        jdbc.update("DELETE FROM word_pronunciations WHERE word_revision_id=?", revisionId);
        Path manifest = prepareManifest(directory, null);
        Path audio = directory.resolve("sample.mp3");
        when(storage.bucket())
                .thenAnswer(
                        invocation -> {
                            byte[] bytes = Files.readAllBytes(audio);
                            bytes[3] = 1;
                            Files.write(audio, bytes);
                            return "media-fixture";
                        });

        assertThatThrownBy(() -> importer.importManifest(manifest, admin.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("checksum");
        org.mockito.Mockito.verify(storage, org.mockito.Mockito.never())
                .put(anyString(), org.mockito.ArgumentMatchers.any(), anyString());
    }

    @Test
    void validImportFlushesItsSourceAndRepeatsWithoutChangingPublishedContent(
            @TempDir Path directory) throws Exception {
        jdbc.update("DELETE FROM word_pronunciations WHERE word_revision_id=?", revisionId);
        Path manifest = prepareManifest(directory, null);
        String before = reader.snapshot(wordId, revisionId, "WORD", false).orElseThrow();
        assertThat(importer.importManifest(manifest, admin.getId())).isEqualTo(1);
        assertThat(importer.importManifest(manifest, admin.getId())).isZero();
        assertThat(reader.snapshot(wordId, revisionId, "WORD", false)).contains(before);
        assertThat(pronunciation.audioUrl(wordId, revisionId)).isNotNull();
        org.mockito.Mockito.verify(storage, org.mockito.Mockito.times(1))
                .put(anyString(), org.mockito.ArgumentMatchers.any(), anyString());
    }

    private Path prepareManifest(Path directory, String checksum) throws Exception {
        var bytes = new byte[256];
        bytes[0] = 'I';
        bytes[1] = 'D';
        bytes[2] = '3';
        Files.write(directory.resolve("sample.mp3"), bytes);
        var root = mapper.createObjectNode();
        root.put("voiceId", "test-voice")
                .put("locale", "en-GB")
                .put("modelSha256", "a".repeat(64))
                .put("sourceUrl", "https://example.com/model")
                .put("licenseCode", "TEST")
                .put("attribution", "Test audio");
        root.putArray("entries")
                .addObject()
                .put("wordId", wordId.toString())
                .put("wordRevisionId", revisionId.toString())
                .put("lemma", "approach")
                .put("file", "sample.mp3")
                .put("durationMs", 1200)
                .put(
                        "sha256",
                        checksum != null
                                ? checksum
                                : java.util.HexFormat.of()
                                        .formatHex(
                                                java.security.MessageDigest.getInstance("SHA-256")
                                                        .digest(bytes)));
        Path manifest = directory.resolve("manifest.json");
        Files.writeString(manifest, root.toString());
        return manifest;
    }

    @Test
    void senseLevelAudio_twoSensesDifferentObjects_respectsEntitlementAndRevoke() throws Exception {
        UUID senseWordId = UUID.randomUUID();
        UUID senseRev1Id = UUID.randomUUID();
        UUID sense1Id = UUID.randomUUID();
        UUID sense2Id = UUID.randomUUID();
        UUID asset1Id = UUID.randomUUID();
        UUID asset2Id = UUID.randomUUID();
        UUID unknownSenseId = UUID.randomUUID();

        jdbc.update(
                """
                INSERT INTO media_assets(id,media_type,purpose,bucket,object_key,original_filename,
                    mime_type,file_size_bytes,duration_ms,checksum_sha256,source_id,uploaded_by,status)
                VALUES (?,'AUDIO','CONTENT','media-fixture','sense/one.mp3','one.mp3',
                    'audio/mpeg',1024,1000,?,?,?,'READY'),
                       (?,'AUDIO','CONTENT','media-fixture','sense/two.mp3','two.mp3',
                    'audio/mpeg',1024,1000,?,?,?,'READY')
                """,
                asset1Id,
                "b".repeat(64),
                VocabularySeedServiceImpl.DEFAULT_SOURCE_ID,
                admin.getId(),
                asset2Id,
                "c".repeat(64),
                VocabularySeedServiceImpl.DEFAULT_SOURCE_ID,
                admin.getId());

        String snapshot =
                """
                {
                  "schema_version": 1,
                  "content_type": "WORD",
                  "data": {
                    "lemma": "record",
                    "language_code": "en",
                    "ipa": "/ˈrek.ɔːd/",
                    "senses": [
                      {
                        "id": "%s",
                        "sense_no": 1,
                        "part_of_speech": "NOUN",
                        "cefr_level": "B1",
                        "meaning_vi": "bản ghi",
                        "audio_media_id": "%s",
                        "examples": []
                      },
                      {
                        "id": "%s",
                        "sense_no": 2,
                        "part_of_speech": "VERB",
                        "cefr_level": "B1",
                        "meaning_vi": "ghi âm",
                        "audio_media_id": "%s",
                        "examples": []
                      }
                    ]
                  }
                }
                """
                        .formatted(sense1Id, asset1Id, sense2Id, asset2Id);

        jdbc.update(
                "INSERT INTO content_items(id, content_type, created_by, import_namespace, external_key) VALUES (?, 'WORD', ?, 'sense-test', 'record')",
                senseWordId,
                admin.getId());
        jdbc.update(
                "INSERT INTO content_revisions(id, content_item_id, version_no, schema_version, snapshot, status, created_by, reviewed_by, reviewed_at) VALUES (?, ?, 1, 1, CAST(? AS jsonb), 'APPROVED', ?, ?, NOW())",
                senseRev1Id,
                senseWordId,
                snapshot,
                admin.getId(),
                admin.getId());
        jdbc.update(
                "INSERT INTO revision_media_refs(revision_id, media_id, usage_role) VALUES (?, ?, 'EXPLANATION'), (?, ?, 'EXPLANATION')",
                senseRev1Id,
                asset1Id,
                senseRev1Id,
                asset2Id);
        jdbc.update(
                "UPDATE content_revisions SET status = 'PUBLISHED', published_at = NOW() WHERE id = ?",
                senseRev1Id);
        jdbc.update(
                "UPDATE content_items SET published_revision_id = ? WHERE id = ?",
                senseRev1Id,
                senseWordId);

        when(storage.signGet("sense/one.mp3"))
                .thenReturn(URI.create("https://example.com/sense/one.mp3"));
        when(storage.signGet("sense/two.mp3"))
                .thenReturn(URI.create("https://example.com/sense/two.mp3"));

        // 1. Distinct URLs per sense; unknown sense rejected
        assertThat(pronunciation.audioUrl(senseWordId, senseRev1Id, sense1Id))
                .isEqualTo(
                        "/api/v1/vocabulary/pronunciations/"
                                + senseRev1Id
                                + "?senseId="
                                + sense1Id);
        assertThat(pronunciation.audioUrl(senseWordId, senseRev1Id, sense2Id))
                .isEqualTo(
                        "/api/v1/vocabulary/pronunciations/"
                                + senseRev1Id
                                + "?senseId="
                                + sense2Id);
        assertThat(pronunciation.audioUrl(senseWordId, senseRev1Id, unknownSenseId)).isNull();

        // 2. Ticket and playback resolve exact object for each sense; unknown sense -> 404
        mvc.perform(
                        get("/api/v1/vocabulary/pronunciations/" + senseRev1Id + "/ticket")
                                .param("senseId", sense1Id.toString())
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                UserPrincipal.create(learner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://example.com/sense/one.mp3"));

        mvc.perform(
                        get("/api/v1/vocabulary/pronunciations/" + senseRev1Id)
                                .param("senseId", sense2Id.toString())
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                UserPrincipal.create(learner))))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/sense/two.mp3"));

        mvc.perform(
                        get("/api/v1/vocabulary/pronunciations/" + senseRev1Id + "/ticket")
                                .param("senseId", unknownSenseId.toString())
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                UserPrincipal.create(learner))))
                .andExpect(status().isNotFound());

        // 3. After v2 is published, v1 still playable for history
        UUID senseRev2Id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO content_revisions(id, content_item_id, version_no, schema_version, snapshot, status, created_by, reviewed_by, reviewed_at, published_at) VALUES (?, ?, 2, 1, CAST(? AS jsonb), 'PUBLISHED', ?, ?, NOW(), NOW())",
                senseRev2Id,
                senseWordId,
                snapshot,
                admin.getId(),
                admin.getId());
        jdbc.update(
                "UPDATE content_items SET published_revision_id = ? WHERE id = ?",
                senseRev2Id,
                senseWordId);

        mvc.perform(
                        get("/api/v1/vocabulary/pronunciations/" + senseRev1Id + "/ticket")
                                .param("senseId", sense1Id.toString())
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                UserPrincipal.create(learner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://example.com/sense/one.mp3"));

        // 4. Revoke blocks both ticket and playback
        jdbc.update(
                "UPDATE content_items SET access_revoked_at = NOW(), access_revoke_reason = 'Test revoke' WHERE id = ?",
                senseWordId);
        mvc.perform(
                        get("/api/v1/vocabulary/pronunciations/" + senseRev1Id + "/ticket")
                                .param("senseId", sense1Id.toString())
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                UserPrincipal.create(learner))))
                .andExpect(status().isNotFound());
        mvc.perform(
                        get("/api/v1/vocabulary/pronunciations/" + senseRev1Id)
                                .param("senseId", sense1Id.toString())
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                UserPrincipal.create(learner))))
                .andExpect(status().isNotFound());
    }
}
