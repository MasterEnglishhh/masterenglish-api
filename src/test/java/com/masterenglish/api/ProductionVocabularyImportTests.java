package com.masterenglish.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.masterenglish.api.common.storage.PronunciationStorage;
import com.masterenglish.api.modules.content.authoring.ContentAuthoringService;
import com.masterenglish.api.modules.content.authoring.PublishedContentReader;
import com.masterenglish.api.modules.content.pronunciation.LocalPronunciationImporter;
import com.masterenglish.api.modules.content.pronunciation.PronunciationQuery;
import com.masterenglish.api.modules.identity.entity.Role;
import com.masterenglish.api.modules.identity.entity.User;
import com.masterenglish.api.modules.identity.repository.UserRepository;
import com.masterenglish.api.modules.identity.user.UserLockService;
import com.masterenglish.api.modules.vocabulary.query.VocabularyQueryService;
import com.masterenglish.api.modules.vocabulary.seed.ProductionVocabularyImportService;
import com.masterenglish.api.modules.vocabulary.seed.VocabularySeedService;
import jakarta.persistence.EntityManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ProductionVocabularyImportTests {
    @Autowired ApplicationContext context;
    @Autowired UserRepository users;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired UserLockService lock;
    @Autowired VocabularySeedService seed;
    @Autowired PublishedContentReader reader;
    @Autowired ContentAuthoringService authoring;
    @Autowired PronunciationQuery pronunciation;
    @Autowired VocabularyQueryService vocabulary;
    @MockitoBean PronunciationStorage storage;

    @Test
    void completePortableBundleUsesTargetRevisionsAndCanBeRepeated(@TempDir Path directory)
            throws Exception {
        User actor = account(true);
        User learner = account(false);
        int usersBefore = count("users");
        int rolesBefore = count("user_roles");
        Path manifest = bundle(directory);
        when(storage.bucket()).thenReturn("media-fixture");
        var result = importer().importStarter(actor.getEmail(), manifest);
        assertThat(result.words()).isEqualTo(59);
        assertThat(result.decks()).isEqualTo(6);
        assertThat(result.audioImported()).isEqualTo(59);
        assertThat(count("word_pronunciations")).isEqualTo(59);
        assertThat(count("words")).isEqualTo(59);
        assertThat(count("decks")).isEqualTo(6);
        assertThat(count("users")).isEqualTo(usersBefore);
        assertThat(count("user_roles")).isEqualTo(rolesBefore);
        assertThat(count("user_word_progress")).isZero();
        assertThat(users.findById(learner.getId()).orElseThrow().getRoleNames())
                .containsExactly("LEARNER");
        assertThat(importer().importStarter(actor.getEmail(), manifest).audioImported()).isZero();
        assertThat(count("content_revisions")).isEqualTo(65);
    }

    @Test
    void partialBundleIsRejectedBeforePublishingOrUploading(@TempDir Path directory)
            throws Exception {
        User actor = account(true);
        Path manifest = bundle(directory);
        var root = mapper.readTree(Files.readString(manifest));
        ((com.fasterxml.jackson.databind.node.ArrayNode) root.path("entries")).remove(0);
        Files.writeString(manifest, root.toString());
        assertThatThrownBy(() -> importer().importStarter(actor.getEmail(), manifest))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly");
        assertThat(count("content_items")).isZero();
        verifyNoInteractions(storage);
    }

    @Test
    void badChecksumIsRejectedBeforePublishingOrUploading(@TempDir Path directory)
            throws Exception {
        User actor = account(true);
        Path manifest = bundle(directory);
        Files.write(directory.resolve("sample.mp3"), new byte[256]);
        assertThatThrownBy(() -> importer().importStarter(actor.getEmail(), manifest))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(count("content_items")).isZero();
        verifyNoInteractions(storage);
    }

    @Test
    void learnerCannotImportAndNormalStartupHasNoProductionImporter(@TempDir Path directory)
            throws Exception {
        User learner = account(false);
        Path manifest = bundle(directory);
        assertThat(context.getBeansOfType(ProductionVocabularyImportService.class)).isEmpty();
        assertThat(context.getBeansOfType(LocalPronunciationImporter.class)).isEmpty();
        assertThatThrownBy(() -> importer().importStarter(learner.getEmail(), manifest))
                .hasMessageContaining("administrator");
        assertThat(count("content_items")).isZero();
        verifyNoInteractions(storage);
    }

    @Test
    void failedUploadRollsBackPublishedContentWithTheWholeImport(@TempDir Path directory)
            throws Exception {
        int before = count("content_items");
        User actor = account(true);
        Path manifest = bundle(directory);
        when(storage.bucket()).thenReturn("media-fixture");
        doThrow(new IllegalStateException("simulated storage outage"))
                .when(storage)
                .put(anyString(), any(), anyString());
        assertThatThrownBy(() -> importer().importStarter(actor.getEmail(), manifest))
                .hasMessageContaining("simulated storage outage");
        // Match the command's transaction boundary and verify persisted state after rollback.
        TestTransaction.flagForRollback();
        TestTransaction.end();
        assertThat(count("content_items")).isEqualTo(before);
        assertThat(count("word_pronunciations")).isZero();
    }

    @Test
    void unsafeAudioPathIsRejected(@TempDir Path directory) throws Exception {
        User actor = account(true);
        Path manifest = bundle(directory);
        var root = mapper.readTree(Files.readString(manifest));
        ((com.fasterxml.jackson.databind.node.ObjectNode) root.path("entries").get(0))
                .put("file", "../sample.mp3");
        Files.writeString(manifest, root.toString());
        assertThatThrownBy(() -> importer().importStarter(actor.getEmail(), manifest))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("filename");
        assertThat(count("content_items")).isZero();
    }

    @Test
    void operatorGrantKeepsLearnerRoleAndInvalidatesAuthenticationOnlyOnce() throws Exception {
        User learner = account(false);
        int authVersion = learner.getAuthVersion();
        jdbc.queryForObject(
                "SELECT set_config('app.content_import.account_email', ?, true)",
                String.class,
                learner.getEmail());
        String script = Files.readString(Path.of("scripts/content/grant_content_admin.sql"));
        String operation = script.substring(script.indexOf("DO $$"), script.indexOf("END $$;") + 7);
        jdbc.execute(operation);
        jdbc.execute(operation);
        em.clear();
        var updated = users.findById(learner.getId()).orElseThrow();
        assertThat(updated.getRoleNames()).containsExactlyInAnyOrder("LEARNER", "CONTENT_ADMIN");
        assertThat(updated.getAuthVersion()).isEqualTo(authVersion + 1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM audit_logs WHERE entity_id=? AND action='GRANT_CONTENT_ADMIN'",
                                Integer.class,
                                learner.getId()))
                .isEqualTo(1);
    }

    @Test
    void operatorGrantRejectsAnUnverifiedAccount() throws Exception {
        var pending =
                users.saveAndFlush(
                        new User(
                                UUID.randomUUID() + "@example.com",
                                "unused-test-only",
                                "Pending",
                                "Fixture"));
        jdbc.queryForObject(
                "SELECT set_config('app.content_import.account_email', ?, true)",
                String.class,
                pending.getEmail());
        String script = Files.readString(Path.of("scripts/content/grant_content_admin.sql"));
        String operation = script.substring(script.indexOf("DO $$"), script.indexOf("END $$;") + 7);
        assertThatThrownBy(() -> jdbc.execute(operation))
                .hasMessageContaining("ACTIVE, email-verified");
    }

    private User account(boolean admin) {
        var user =
                new User(
                        UUID.randomUUID() + "@example.com",
                        "unused-test-only",
                        "Import",
                        "Fixture");
        user.addRole(em.find(Role.class, (short) (admin ? 2 : 1)));
        user.markEmailVerified(Instant.now());
        return users.saveAndFlush(user);
    }

    private Path bundle(Path directory) throws Exception {
        byte[] bytes = new byte[256];
        bytes[0] = 'I';
        bytes[1] = 'D';
        bytes[2] = '3';
        Files.write(directory.resolve("sample.mp3"), bytes);
        String checksum =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        var root = mapper.createObjectNode();
        root.put("bundleType", "masterenglish-starter-v1")
                .put("voiceId", "test-voice")
                .put("locale", "en-GB")
                .put("modelSha256", "a".repeat(64))
                .put("sourceUrl", "https://example.com/model")
                .put("licenseCode", "TEST")
                .put("attribution", "Fixture audio");
        var entries = root.putArray("entries");
        seed.starterWords()
                .forEach(
                        (id, lemma) ->
                                entries.addObject()
                                        .put("wordId", id.toString())
                                        .put("lemma", lemma)
                                        .put("file", "sample.mp3")
                                        .put("durationMs", 1200)
                                        .put("sha256", checksum));
        Path manifest = directory.resolve("manifest.json");
        Files.writeString(manifest, root.toString());
        return manifest;
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private ProductionVocabularyImportService importer() {
        var audio =
                new LocalPronunciationImporter(
                        context.getBeanProvider(PronunciationStorage.class),
                        jdbc,
                        mapper,
                        lock,
                        reader,
                        authoring,
                        em);
        var target =
                new ProductionVocabularyImportService(
                        jdbc, mapper, lock, seed, audio, pronunciation, vocabulary);
        var proxy = new org.springframework.aop.framework.ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(
                new org.springframework.transaction.interceptor.TransactionInterceptor(
                        context.getBean(
                                org.springframework.transaction.PlatformTransactionManager.class),
                        new org.springframework.transaction.annotation
                                .AnnotationTransactionAttributeSource()));
        return (ProductionVocabularyImportService) proxy.getProxy();
    }
}
