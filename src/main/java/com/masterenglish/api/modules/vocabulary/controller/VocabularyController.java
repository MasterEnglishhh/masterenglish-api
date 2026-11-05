package com.masterenglish.api.modules.vocabulary.controller;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.common.pagination.PageRequestUtils;
import com.masterenglish.api.modules.vocabulary.query.DeckDetailDto;
import com.masterenglish.api.modules.vocabulary.query.DeckDto;
import com.masterenglish.api.modules.vocabulary.query.VocabularyQueryService;
import com.masterenglish.api.modules.vocabulary.query.WordDetailDto;
import com.masterenglish.api.modules.vocabulary.query.WordListDto;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/vocabulary")
@PreAuthorize("hasRole('LEARNER')")
public class VocabularyController {

    private static final Set<String> ALLOWED_WORD_SORT = Set.of("lemma", "createdAt", "id");
    private static final Set<String> ALLOWED_DECK_SORT = Set.of("title", "createdAt", "id");

    private final VocabularyQueryService queryService;

    public VocabularyController(VocabularyQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/words")
    public PageResponse<WordListDto> getWords(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String cefrLevel,
            @RequestParam(required = false) String partOfSpeech,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDirection) {
        Pageable pageable =
                PageRequestUtils.of(
                        page,
                        size,
                        sortBy != null ? sortBy : "lemma",
                        sortDirection != null ? sortDirection : "asc",
                        ALLOWED_WORD_SORT);
        return queryService.getWords(q, cefrLevel, partOfSpeech, pageable);
    }

    @GetMapping("/words/{wordId}")
    public WordDetailDto getWordDetail(@PathVariable UUID wordId) {
        return queryService.getWordDetail(wordId);
    }

    @GetMapping("/decks")
    public PageResponse<DeckDto> getDecks(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDirection) {
        Pageable pageable =
                PageRequestUtils.of(
                        page,
                        size,
                        sortBy != null ? sortBy : "createdAt",
                        sortDirection != null ? sortDirection : "desc",
                        ALLOWED_DECK_SORT);
        return queryService.getDecks(q, level, pageable);
    }

    @GetMapping("/decks/{deckId}")
    public DeckDetailDto getDeckDetail(@PathVariable UUID deckId) {
        return queryService.getDeckDetail(deckId);
    }
}
