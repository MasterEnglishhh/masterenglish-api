package com.masterenglish.api.modules.vocabulary.seed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.masterenglish.api.modules.content.pronunciation.LocalPronunciationImporter;
import com.masterenglish.api.modules.content.pronunciation.PronunciationQuery;
import com.masterenglish.api.modules.identity.user.UserLockService;
import com.masterenglish.api.modules.vocabulary.query.VocabularyQueryService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("content-import")
public class ProductionVocabularyImportService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final UserLockService userLock;
    private final VocabularySeedService seed;
    private final LocalPronunciationImporter audio;
    private final PronunciationQuery pronunciation;
    private final VocabularyQueryService vocabulary;

    public ProductionVocabularyImportService(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            UserLockService userLock,
            VocabularySeedService seed,
            LocalPronunciationImporter audio,
            PronunciationQuery pronunciation,
            VocabularyQueryService vocabulary) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.userLock = userLock;
        this.seed = seed;
        this.audio = audio;
        this.pronunciation = pronunciation;
        this.vocabulary = vocabulary;
    }

    public record Result(int words, int decks, int audioImported) {}

    @Transactional(rollbackFor = Exception.class)
    public Result importStarter(String actorEmail, Path manifest) throws IOException {
        if (!Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT pg_try_advisory_xact_lock(63264573194821001)", Boolean.class))) {
            throw new IllegalStateException("Another starter import is running.");
        }
        var actors =
                jdbc.queryForList(
                        "SELECT id FROM users WHERE email=?",
                        UUID.class,
                        actorEmail.strip().toLowerCase(Locale.ROOT));
        if (actors.size() != 1) throw new IllegalArgumentException("Import account not found.");
        UUID actor = actors.getFirst();
        userLock.verifyContentAdmin(actor);
        var bundle = mapper.readTree(Files.readString(manifest));
        if (!bundle.path("bundleType").asText().equals("masterenglish-starter-v1")
                || !bundle.path("locale").asText().equals("en-GB")) {
            throw new IllegalArgumentException("Use the portable en-GB starter bundle.");
        }
        var expected = seed.starterWords();
        if (!expected.equals(audio.validateManifest(manifest))) {
            throw new IllegalArgumentException(
                    "Bundle must contain exactly the starter words and matching lemmas.");
        }
        seed.seedTopicVocabularyData(actor);
        int imported = audio.importPortableManifest(manifest, actor);
        for (UUID wordId : expected.keySet()) {
            UUID revision =
                    jdbc.queryForObject(
                            "SELECT published_revision_id FROM words WHERE id=?",
                            UUID.class,
                            wordId);
            if (pronunciation.audioUrl(wordId, revision) == null) {
                throw new IllegalStateException("Imported word has no playable pronunciation.");
            }
        }
        for (UUID deckId : seed.starterDeckIds()) vocabulary.getDeckDetail(deckId);
        return new Result(expected.size(), seed.starterDeckIds().size(), imported);
    }
}
