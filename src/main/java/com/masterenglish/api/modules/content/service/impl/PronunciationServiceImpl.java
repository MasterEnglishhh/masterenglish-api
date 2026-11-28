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
    public String audioUrl(UUID wordId, UUID revisionId, UUID senseId) {
        if (senseId == null) {
            return audioUrl(wordId, revisionId);
        }
        var backend = storage.getIfAvailable();
        if (backend == null) return null;
        String senseIdStr = senseId.toString();
        if (!repository.hasSenseInRevision(revisionId, senseIdStr)) {
            return null;
        }
        var senseAsset =
                repository
                        .findPlayableSenseAsset(revisionId, senseIdStr)
                        .filter(
                                a ->
                                        a.getWordId().equals(wordId)
                                                && a.getBucket().equals(backend.bucket()));
        if (senseAsset.isPresent()) {
            return "/api/v1/vocabulary/pronunciations/" + revisionId + "?senseId=" + senseId;
        }
        // Legacy fallback only within the same revision (Word-level audio)
        return audioUrl(wordId, revisionId);
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
                        .filter(a -> repository.isPlaybackAllowed(a.getWordId(), revisionId))
                        .orElseThrow(PronunciationServiceImpl::unavailable);
        return backend.signGet(found.getKey());
    }

    @Override
    @Transactional(readOnly = true)
    public URI playbackLocation(UUID revisionId, UUID senseId) {
        if (senseId == null) {
            return playbackLocation(revisionId);
        }
        log.debug(
                "Resolving sense pronunciation playback for revision {} sense {}",
                revisionId,
                senseId);
        var backend = storage.getIfAvailable();
        if (backend == null) throw unavailable();
        String senseIdStr = senseId.toString();
        if (!repository.hasSenseInRevision(revisionId, senseIdStr)) {
            throw unavailable();
        }
        var found =
                repository
                        .findPlayableSenseAsset(revisionId, senseIdStr)
                        .or(() -> repository.findPlayableAsset(revisionId))
                        .filter(a -> a.getBucket().equals(backend.bucket()))
                        .filter(a -> repository.isPlaybackAllowed(a.getWordId(), revisionId))
                        .orElseThrow(PronunciationServiceImpl::unavailable);
        return backend.signGet(found.getKey());
    }

    private static BusinessException unavailable() {
        return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Pronunciation unavailable.");
    }
}
