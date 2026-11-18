package com.masterenglish.api.modules.learning.service;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.modules.learning.dto.response.SavedWordDto;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;

public interface SavedWordService {

    void saveWord(UUID userId, UUID wordSenseId);

    void unsaveWord(UUID userId, UUID wordSenseId);

    PageResponse<SavedWordDto> getSavedWords(UUID userId, String cefrLevel, Pageable pageable);

    List<UUID> getSavedSenseIdsForWord(UUID userId, UUID wordId);
}
