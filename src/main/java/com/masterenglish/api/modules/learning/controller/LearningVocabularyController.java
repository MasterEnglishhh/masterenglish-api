package com.masterenglish.api.modules.learning.controller;

import com.masterenglish.api.common.api.PageResponse;
import com.masterenglish.api.common.api.WordSenseCardDto;
import com.masterenglish.api.common.pagination.PageRequestUtils;
import com.masterenglish.api.common.security.AuthenticatedUser;
import com.masterenglish.api.common.security.CurrentUser;
import com.masterenglish.api.modules.learning.dto.request.ReviewRequest;
import com.masterenglish.api.modules.learning.dto.response.DueQueueResponse;
import com.masterenglish.api.modules.learning.dto.response.ReviewResponse;
import com.masterenglish.api.modules.learning.dto.response.SavedSenseIdsResponse;
import com.masterenglish.api.modules.learning.dto.response.SavedWordDto;
import com.masterenglish.api.modules.learning.service.DueQueueService;
import com.masterenglish.api.modules.learning.service.LearningDeckCardService;
import com.masterenglish.api.modules.learning.service.ReviewService;
import com.masterenglish.api.modules.learning.service.SavedWordService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/vocabulary")
@PreAuthorize("hasRole('LEARNER')")
public class LearningVocabularyController {

    private final LearningDeckCardService deckCardService;
    private final SavedWordService savedWordService;
    private final DueQueueService dueQueueService;
    private final ReviewService reviewService;

    public LearningVocabularyController(
            LearningDeckCardService deckCardService,
            SavedWordService savedWordService,
            DueQueueService dueQueueService,
            ReviewService reviewService) {
        this.deckCardService = deckCardService;
        this.savedWordService = savedWordService;
        this.dueQueueService = dueQueueService;
        this.reviewService = reviewService;
    }

    @GetMapping("/decks/{deckId}/words")
    public PageResponse<WordSenseCardDto> getDeckWords(
            @CurrentUser AuthenticatedUser currentUser,
            @PathVariable UUID deckId,
            @RequestParam(required = false) String cefrLevel,
            @RequestParam(required = false) String partOfSpeech,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        Pageable pageable = PageRequestUtils.of(page, size);
        return deckCardService.getDeckCardsWithProgress(
                currentUser.getId(), deckId, cefrLevel, partOfSpeech, pageable);
    }

    @GetMapping("/saved-words")
    public PageResponse<SavedWordDto> getSavedWords(
            @CurrentUser AuthenticatedUser currentUser,
            @RequestParam(required = false) String cefrLevel,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        Pageable pageable = PageRequestUtils.of(page, size);
        return savedWordService.getSavedWords(currentUser.getId(), cefrLevel, pageable);
    }

    @GetMapping("/words/{wordId}/saved-senses")
    public SavedSenseIdsResponse getSavedSenses(
            @CurrentUser AuthenticatedUser currentUser, @PathVariable UUID wordId) {
        return new SavedSenseIdsResponse(
                savedWordService.getSavedSenseIdsForWord(currentUser.getId(), wordId));
    }

    @PutMapping("/saved-words/{senseId}")
    public ResponseEntity<Void> saveWord(
            @CurrentUser AuthenticatedUser currentUser, @PathVariable UUID senseId) {
        savedWordService.saveWord(currentUser.getId(), senseId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/saved-words/{senseId}")
    public ResponseEntity<Void> unsaveWord(
            @CurrentUser AuthenticatedUser currentUser, @PathVariable UUID senseId) {
        savedWordService.unsaveWord(currentUser.getId(), senseId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/reviews/due")
    public DueQueueResponse getDueQueue(
            @CurrentUser AuthenticatedUser currentUser,
            @RequestParam(required = false) UUID deckId,
            @RequestParam(required = false, defaultValue = "20") int limit) {
        return dueQueueService.getDueQueue(currentUser.getId(), deckId, limit);
    }

    @PostMapping("/reviews")
    public ResponseEntity<ReviewResponse> reviewCard(
            @CurrentUser AuthenticatedUser currentUser, @Valid @RequestBody ReviewRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(reviewService.reviewCard(currentUser.getId(), request));
    }
}
