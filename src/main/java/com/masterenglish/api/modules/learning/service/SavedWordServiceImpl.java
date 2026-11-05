package com.masterenglish.api.modules.learning.service;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.modules.learning.dto.SavedWordDto;
import com.masterenglish.api.modules.learning.entity.UserSavedWord;
import com.masterenglish.api.modules.learning.repository.UserSavedWordRepository;
import com.masterenglish.api.modules.vocabulary.query.VocabularyQueryService;
import com.masterenglish.api.modules.vocabulary.query.WordSenseCardDto;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class SavedWordServiceImpl implements SavedWordService {

    private final UserSavedWordRepository savedWordRepository;
    private final VocabularyQueryService vocabularyQueryService;

    public SavedWordServiceImpl(
            UserSavedWordRepository savedWordRepository,
            VocabularyQueryService vocabularyQueryService) {
        this.savedWordRepository = savedWordRepository;
        this.vocabularyQueryService = vocabularyQueryService;
    }

    @Override
    public void saveWord(UUID userId, UUID wordSenseId) {
        Optional<WordSenseCardDto> card = vocabularyQueryService.getCardBySenseId(wordSenseId);
        if (card.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.RESOURCE_NOT_FOUND, "Word sense not found: " + wordSenseId);
        }

        savedWordRepository.insertIfAbsent(userId, wordSenseId);
    }

    @Override
    public void unsaveWord(UUID userId, UUID wordSenseId) {
        savedWordRepository.deleteByUserIdAndWordSenseId(userId, wordSenseId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> getSavedSenseIdsForWord(UUID userId, UUID wordId) {
        vocabularyQueryService.getWordDetail(wordId);
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
                    vocabularyQueryService.getCardBySenseId(sw.getId().getWordSenseId());
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
