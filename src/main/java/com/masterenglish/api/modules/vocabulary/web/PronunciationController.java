package com.masterenglish.api.modules.vocabulary.web;

import com.masterenglish.api.modules.content.pronunciation.PronunciationQuery;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@PreAuthorize("hasRole('LEARNER')")
public class PronunciationController {
    private final PronunciationQuery query;

    public PronunciationController(PronunciationQuery query) {
        this.query = query;
    }

    @GetMapping("/api/v1/vocabulary/pronunciations/{revisionId}")
    public ResponseEntity<Void> playback(@PathVariable UUID revisionId) {
        return ResponseEntity.status(302)
                .location(query.playbackLocation(revisionId))
                .cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .build();
    }

    @GetMapping("/api/v1/vocabulary/pronunciations/{revisionId}/ticket")
    public ResponseEntity<java.util.Map<String, String>> ticket(@PathVariable UUID revisionId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(java.util.Map.of("url", query.playbackLocation(revisionId).toString()));
    }
}
