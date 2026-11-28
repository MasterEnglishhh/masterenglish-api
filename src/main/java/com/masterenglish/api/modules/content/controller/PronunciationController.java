package com.masterenglish.api.modules.content.controller;

import com.masterenglish.api.modules.content.dto.response.PronunciationTicketResponse;
import com.masterenglish.api.modules.content.service.PronunciationService;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/vocabulary/pronunciations/{revisionId}")
@PreAuthorize("hasRole('LEARNER')")
public class PronunciationController {
    private final PronunciationService pronunciation;

    public PronunciationController(PronunciationService pronunciation) {
        this.pronunciation = pronunciation;
    }

    @GetMapping
    public ResponseEntity<Void> playback(
            @PathVariable UUID revisionId, @RequestParam(required = false) UUID senseId) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(pronunciation.playbackLocation(revisionId, senseId))
                .cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .build();
    }

    @GetMapping("/ticket")
    public ResponseEntity<PronunciationTicketResponse> ticket(
            @PathVariable UUID revisionId, @RequestParam(required = false) UUID senseId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(
                        new PronunciationTicketResponse(
                                pronunciation.playbackLocation(revisionId, senseId).toString()));
    }
}
