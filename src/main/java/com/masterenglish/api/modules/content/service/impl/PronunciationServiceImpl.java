package com.masterenglish.api.modules.content.service.impl;

import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.common.storage.PronunciationStorage;
import com.masterenglish.api.modules.content.repository.ContentRevisionRepository;
import com.masterenglish.api.modules.content.service.PronunciationService;
import java.net.URI;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class PronunciationServiceImpl implements PronunciationService {
    private final ContentRevisionRepository repository;
    private final ObjectProvider<PronunciationStorage> storage;

    public PronunciationServiceImpl(
            ContentRevisionRepository repository, ObjectProvider<PronunciationStorage> storage) {
        this.repository = repository;
        this.storage = storage;
    }

    @Override
    @Transactional(readOnly = true)
    public String audioUrl(UUID wordId, UUID revisionId) {
        var backend = storage.getIfAvailable();
        if (backend == null) return null;
        return repository
                .findPlayableAsset(revisionId)
                .filter(a -> a.getWordId().equals(wordId) && a.getBucket().equals(backend.bucket()))
                .map(a -> "/api/v1/vocabulary/pronunciations/" + revisionId)
                .orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public URI playbackLocation(UUID revisionId) {
        log.debug("Resolving pronunciation playback for revision {}", revisionId);
        var backend = storage.getIfAvailable();
        if (backend == null) throw unavailable();
        var found =
                repository
                        .findPlayableAsset(revisionId)
                        .filter(a -> a.getBucket().equals(backend.bucket()))
                        .filter(
                                a ->
                                        repository
                                                .snapshot(a.getWordId(), revisionId, "WORD", false)
                                                .isPresent())
                        .orElseThrow(PronunciationServiceImpl::unavailable);
        return backend.signGet(found.getKey());
    }

    private static BusinessException unavailable() {
        return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Pronunciation unavailable.");
    }
}
