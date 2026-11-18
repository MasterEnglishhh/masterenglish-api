package com.masterenglish.api.modules.vocabulary.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.masterenglish.api.common.events.ContentEvents;
import com.masterenglish.api.common.events.IdentityEvents;
import com.masterenglish.api.modules.vocabulary.dto.response.ContentImportResult;
import com.masterenglish.api.modules.vocabulary.repository.WordRepository;
import com.masterenglish.api.modules.vocabulary.service.ProductionVocabularyImportService;
import com.masterenglish.api.modules.vocabulary.service.VocabularyQueryService;
import com.masterenglish.api.modules.vocabulary.service.VocabularySeedService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Profile("content-import")
public class ProductionVocabularyImportServiceImpl implements ProductionVocabularyImportService {
    private final WordRepository repository;
    private final ObjectMapper mapper;
    private final IdentityEvents identity;
    private final VocabularySeedService seed;
    private final ContentEvents content;
    private final VocabularyQueryService vocabulary;

    public ProductionVocabularyImportServiceImpl(
            WordRepository repository,
            ObjectMapper mapper,
            IdentityEvents identity,
            VocabularySeedService seed,
            ContentEvents content,
            VocabularyQueryService vocabulary) {
        this.repository = repository;
        this.mapper = mapper;
        this.identity = identity;
        this.seed = seed;
        this.content = content;
        this.vocabulary = vocabulary;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ContentImportResult importStarter(String actorEmail, Path manifest) throws IOException {
        if (!repository.tryLockStarterImport())
            throw new IllegalStateException("Another starter import is running.");
        UUID actor = identity.resolveContentAdmin(actorEmail);
        var bundle = mapper.readTree(Files.readString(manifest));
        if (!bundle.path("bundleType").asText().equals("masterenglish-starter-v1")
                || !bundle.path("locale").asText().equals("en-GB")) {
            throw new IllegalArgumentException("Use the portable en-GB starter bundle.");
        }
        var expected = seed.starterWords();
        if (!expected.equals(content.validateManifest(manifest))) {
            throw new IllegalArgumentException(
                    "Bundle must contain exactly the starter words and matching lemmas.");
        }
        seed.seedTopicVocabularyData(actor);
        Map<UUID, UUID> targetRevisions = new LinkedHashMap<>();
        for (UUID wordId : expected.keySet()) {
            UUID revision =
                    repository
                            .findPublishedRevisionId(wordId)
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "Starter word revision unavailable."));
            targetRevisions.put(wordId, revision);
        }
        int imported = content.importPortableManifest(manifest, actor, targetRevisions);
        for (var target : targetRevisions.entrySet()) {
            if (content.audioUrl(target.getKey(), target.getValue()) == null) {
                throw new IllegalStateException("Imported word has no playable pronunciation.");
            }
        }
        for (UUID deckId : seed.starterDeckIds()) vocabulary.getDeckDetail(deckId);
        log.info("Starter import prepared: words={}, audioImported={}", expected.size(), imported);
        return new ContentImportResult(expected.size(), seed.starterDeckIds().size(), imported);
    }
}
