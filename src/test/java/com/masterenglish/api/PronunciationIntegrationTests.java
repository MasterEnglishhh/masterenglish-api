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
import com.masterenglish.api.modules.content.authoring.ContentAuthoringService;
import com.masterenglish.api.modules.content.authoring.PublishedContentReader;
import com.masterenglish.api.modules.content.pronunciation.LocalPronunciationImporter;
import com.masterenglish.api.modules.content.pronunciation.PronunciationQuery;
import com.masterenglish.api.modules.identity.entity.Role;
import com.masterenglish.api.modules.identity.entity.User;
import com.masterenglish.api.modules.identity.repository.UserRepository;
import com.masterenglish.api.modules.identity.security.UserPrincipal;
import com.masterenglish.api.modules.identity.user.UserLockService;
import com.masterenglish.api.modules.vocabulary.query.VocabularyQueryService;
import com.masterenglish.api.modules.vocabulary.seed.VocabularySeedService;
import com.masterenglish.api.modules.vocabulary.seed.VocabularySeedServiceImpl;
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
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class PronunciationIntegrationTests {
    @Autowired WebApplicationContext web;
    @Autowired ApplicationContext context;
    @Autowired UserRepository users;
    @Autowired EntityManager em;
    @Autowired VocabularySeedService seed;
    @Autowired VocabularyQueryService vocabulary;
    @Autowired PronunciationQuery pronunciation;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired UserLockService lock;
    @Autowired PublishedContentReader reader;
    @Autowired ContentAuthoringService authoring;
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
        assertThatThrownBy(() -> importer().importManifest(manifest, admin.getId()))
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
        assertThat(importer().importManifest(manifest, admin.getId())).isEqualTo(1);
        assertThat(importer().importManifest(manifest, admin.getId())).isZero();
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

    private LocalPronunciationImporter importer() {
        return new LocalPronunciationImporter(
                context.getBeanProvider(PronunciationStorage.class),
                jdbc,
                mapper,
                lock,
                reader,
                authoring,
                em);
    }
}
