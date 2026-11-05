package com.masterenglish.api.modules.learning.service;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.modules.learning.dto.SavedWordDto;

import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface SavedWordService {

    void saveWord(UUID userId, UUID wordSenseId);

    void unsaveWord(UUID userId, UUID wordSenseId);

    PageResponse<SavedWordDto> getSavedWords(UUID userId, String cefrLevel, Pageable pageable);

    java.util.List<UUID> getSavedSenseIdsForWord(UUID userId, UUID wordId);
}
