package com.masterenglish.api.modules.learning.service.impl;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.common.api.WordSenseCardDto;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.common.events.VocabularyEvents;
import com.masterenglish.api.modules.learning.dto.response.SavedWordDto;
import com.masterenglish.api.modules.learning.entity.UserSavedWord;
import com.masterenglish.api.modules.learning.repository.UserSavedWordRepository;
import com.masterenglish.api.modules.learning.service.SavedWordService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class SavedWordServiceImpl implements SavedWordService {

    private final UserSavedWordRepository savedWordRepository;
    private final VocabularyEvents vocabulary;

    public SavedWordServiceImpl(
            UserSavedWordRepository savedWordRepository, VocabularyEvents vocabulary) {
        this.savedWordRepository = savedWordRepository;
        this.vocabulary = vocabulary;
    }

    @Override
    @Transactional
    public void saveWord(UUID userId, UUID wordSenseId) {
        log.debug("Saving vocabulary sense {}", wordSenseId);
        Optional<WordSenseCardDto> card = vocabulary.getCardBySenseId(wordSenseId);
        if (card.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.RESOURCE_NOT_FOUND, "Word sense not found: " + wordSenseId);
        }

        savedWordRepository.insertIfAbsent(userId, wordSenseId);
    }

    @Override
    @Transactional
    public void unsaveWord(UUID userId, UUID wordSenseId) {
        savedWordRepository.deleteByUserIdAndWordSenseId(userId, wordSenseId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> getSavedSenseIdsForWord(UUID userId, UUID wordId) {
        vocabulary.requireWord(wordId);
        return savedWordRepository.findSavedSenseIdsForWord(userId, wordId, null);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<SavedWordDto> getSavedWords(
            UUID userId, String cefrLevel, Pageable pageable) {
        Page<UserSavedWord> page = savedWordRepository.findEligible(userId, cefrLevel, pageable);

        List<SavedWordDto> dtos = new ArrayList<>();
        for (UserSavedWord sw : page.getContent()) {
            Optional<WordSenseCardDto> cardOpt =
                    vocabulary.getCardBySenseId(sw.getId().getWordSenseId());
            if (cardOpt.isPresent()) {
                WordSenseCardDto card = cardOpt.get();
                if (cefrLevel == null || cefrLevel.equalsIgnoreCase(card.cefrLevel())) {
                    dtos.add(
                            new SavedWordDto(
                                    card.wordSenseId(),
                                    card.wordId(),
                                    card.lemma(),
                                    card.ipa(),
                                    card.partOfSpeech(),
                                    card.cefrLevel(),
                                    card.meaningVi(),
                                    card.definitionEn(),
                                    sw.getSavedAt()));
                }
            }
        }

        return PageResponse.from(new PageImpl<>(dtos, pageable, page.getTotalElements()));
    }
}
