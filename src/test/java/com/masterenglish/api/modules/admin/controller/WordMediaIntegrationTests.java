package com.masterenglish.api.modules.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.masterenglish.api.TestcontainersConfiguration;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.modules.content.entity.ContentRevision;
import com.masterenglish.api.modules.content.entity.MediaAsset;
import com.masterenglish.api.modules.content.repository.ContentItemRepository;
import com.masterenglish.api.modules.content.repository.ContentRevisionRepository;
import com.masterenglish.api.modules.content.repository.MediaAssetRepository;
import com.masterenglish.api.modules.content.repository.RevisionMediaRefRepository;
import com.masterenglish.api.modules.content.service.ContentAuthoringService;
import com.masterenglish.api.modules.vocabulary.repository.WordRepository;
import com.masterenglish.api.modules.vocabulary.repository.WordSenseRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class WordMediaIntegrationTests {

    @Autowired private ContentAuthoringService authoringService;
    @Autowired private ContentItemRepository itemRepository;
    @Autowired private ContentRevisionRepository revisionRepository;
    @Autowired private MediaAssetRepository mediaAssetRepository;
    @Autowired private RevisionMediaRefRepository revisionMediaRefRepository;
    @Autowired private WordRepository wordRepository;
    @Autowired private WordSenseRepository senseRepository;
    @Autowired private JdbcTemplate jdbc;

    private UUID adminId;
    private UUID sourceId;

    @BeforeEach
    void setUp() {
        adminId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, email, password_hash, first_name, last_name, status, email_verified_at) VALUES (?, ?, ?, ?, ?, 'ACTIVE', NOW())",
                adminId,
                "media-word-admin-" + adminId + "@example.com",
                "$2a$12$e8YkC6XQ8XbY0u9VqT6eIeaFmP/2eZ.z1c8yU2MvX3mN.rLw5QkOe",
                "Media",
                "Admin");
        jdbc.update("INSERT INTO user_roles(user_id, role_id) VALUES (?, 2)", adminId);

        sourceId = UUID.randomUUID();
        authoringService.createSource(
                sourceId,
                "Test Media Audio Source",
                "SELF_AUTHORED",
                null,
                "Test author",
                "SELF_AUTHORED",
                null,
                "Test attribution",
                "Original content",
                adminId);
    }

    private MediaAsset createReadyAudioMedia() {
        UUID mediaId = UUID.randomUUID();
        MediaAsset asset =
                new MediaAsset(
                        mediaId,
                        "AUDIO",
                        "CONTENT",
                        "test-audio.mp3",
                        "audio/mpeg",
                        sourceId,
                        adminId,
                        "PENDING");
        asset.markReady(
                "masterenglish-content",
                "content/" + mediaId + ".mp3",
                1024L,
                "556ec8450ed7e439aa4beabee80176cbf675348a4efb8a45640bc77af943bf3c",
                null,
                null,
                2500);
        return mediaAssetRepository.saveAndFlush(asset);
    }

    @Test
    @DisplayName(
            "Gắn audio_media_id vào Word & Sense: publish thành công, ghi revision_media_refs và chiếu projection")
    void publishWordWithAudio_success() {
        MediaAsset media = createReadyAudioMedia();
        UUID wordId = UUID.randomUUID();
        UUID senseId = UUID.randomUUID();
        UUID exampleId = UUID.randomUUID();

        String snapshot =
                """
                {
                  "schema_version": 1,
                  "content_type": "WORD",
                  "lemma": "ephemeral",
                  "language_code": "en",
                  "ipa": "/ɪˈfem.ər.əl/",
                  "audio_media_id": "%s",
                  "senses": [
                    {
                      "id": "%s",
                      "sense_no": 1,
                      "part_of_speech": "ADJECTIVE",
                      "cefr_level": "C1",
                      "meaning_vi": "phù du, chóng tàn",
                      "definition_en": "lasting for only a short time",
                      "audio_media_id": "%s",
                      "examples": [
                        {
                          "id": "%s",
                          "position": 1,
                          "example_en": "Fame in the world of pop is largely ephemeral.",
                          "example_vi": "Danh tiếng trong giới nhạc pop hầu như chỉ là phù du."
                        }
                      ]
                    }
                  ]
                }
                """
                        .formatted(media.getId(), senseId, media.getId(), exampleId);

        // 1. Create Word Draft
        var item =
                authoringService.createItemWithDraft(
                        wordId,
                        "WORD",
                        adminId,
                        "editor",
                        "ephemeral",
                        snapshot,
                        "Tạo từ vựng kèm audio",
                        sourceId);

        ContentRevision draft =
                revisionRepository.findByContentItemIdOrderByVersionNoDesc(item.getId()).get(0);
        assertThat(draft.getStatus()).isEqualTo("DRAFT");

        // 2. Submit -> IN_REVIEW
        authoringService.submitForReview(draft.getId(), adminId);

        // 3. Approve -> APPROVED
        authoringService.approve(draft.getId(), adminId, "Approved with audio");

        // 4. Publish -> PUBLISHED
        authoringService.publish(draft.getId(), adminId);

        // 5. Verify revision_media_refs recorded (STIMULUS for word, EXPLANATION for sense)
        var mediaRefs = revisionMediaRefRepository.findByRevisionId(draft.getId());
        assertThat(mediaRefs).hasSize(2);
        assertThat(mediaRefs)
                .anyMatch(
                        r ->
                                r.getMediaId().equals(media.getId())
                                        && "STIMULUS".equals(r.getUsageRole()));
        assertThat(mediaRefs)
                .anyMatch(
                        r ->
                                r.getMediaId().equals(media.getId())
                                        && "EXPLANATION".equals(r.getUsageRole()));

        // 6. Verify Word projection
        var word = wordRepository.findById(wordId).orElseThrow();
        assertThat(word.getAudioMediaId()).isEqualTo(media.getId());

        // 7. Verify WordSense projection
        var sense = senseRepository.findById(senseId).orElseThrow();
        assertThat(sense.getAudioMediaId()).isEqualTo(media.getId());

        // 8. Verify findPlayableAsset resolves media asset
        var playable = revisionRepository.findPlayableAsset(draft.getId());
        assertThat(playable).isPresent();
        assertThat(playable.get().getBucket()).isEqualTo("masterenglish-content");
        assertThat(playable.get().getKey()).isEqualTo("content/" + media.getId() + ".mp3");
    }

    @Test
    @DisplayName("Publish Word với media không READY hoặc đã ARCHIVED sẽ bị từ chối")
    void publishWordWithInvalidMedia_rejected() {
        // Pending media
        UUID mediaId = UUID.randomUUID();
        MediaAsset pendingAsset =
                new MediaAsset(
                        mediaId,
                        "AUDIO",
                        "CONTENT",
                        "pending.mp3",
                        "audio/mpeg",
                        sourceId,
                        adminId,
                        "PENDING");
        mediaAssetRepository.saveAndFlush(pendingAsset);

        UUID wordId = UUID.randomUUID();
        UUID senseId = UUID.randomUUID();
        String snapshot =
                """
                {
                  "schema_version": 1,
                  "content_type": "WORD",
                  "lemma": "temporary",
                  "language_code": "en",
                  "audio_media_id": "%s",
                  "senses": [
                    {
                      "id": "%s",
                      "sense_no": 1,
                      "part_of_speech": "ADJECTIVE",
                      "cefr_level": "A2",
                      "meaning_vi": "tạm thời",
                      "examples": []
                    }
                  ]
                }
                """
                        .formatted(mediaId, senseId);

        var item =
                authoringService.createItemWithDraft(
                        wordId,
                        "WORD",
                        adminId,
                        "editor",
                        "temporary",
                        snapshot,
                        "Tạo từ vựng",
                        sourceId);

        ContentRevision draft =
                revisionRepository.findByContentItemIdOrderByVersionNoDesc(item.getId()).get(0);
        authoringService.submitForReview(draft.getId(), adminId);
        authoringService.approve(draft.getId(), adminId, "Approved");

        assertThatThrownBy(() -> authoringService.publish(draft.getId(), adminId))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    @DisplayName(
            "Media bị archive sau khi publish: bảo toàn lịch sử truy cập của revision đã publish")
    void archiveMediaAfterPublish_preservesHistoricalAccess() {
        MediaAsset media = createReadyAudioMedia();
        UUID wordId = UUID.randomUUID();
        UUID senseId = UUID.randomUUID();

        String snapshot =
                """
                {
                  "schema_version": 1,
                  "content_type": "WORD",
                  "lemma": "historic",
                  "language_code": "en",
                  "audio_media_id": "%s",
                  "senses": [
                    {
                      "id": "%s",
                      "sense_no": 1,
                      "part_of_speech": "ADJECTIVE",
                      "cefr_level": "B1",
                      "meaning_vi": "lịch sử",
                      "examples": []
                    }
                  ]
                }
                """
                        .formatted(media.getId(), senseId);

        var item =
                authoringService.createItemWithDraft(
                        wordId,
                        "WORD",
                        adminId,
                        "editor",
                        "historic",
                        snapshot,
                        "Tạo từ vựng",
                        sourceId);

        ContentRevision draft =
                revisionRepository.findByContentItemIdOrderByVersionNoDesc(item.getId()).get(0);
        authoringService.submitForReview(draft.getId(), adminId);
        authoringService.approve(draft.getId(), adminId, "Approved");
        authoringService.publish(draft.getId(), adminId);

        // Now archive the media asset
        media.archive();
        mediaAssetRepository.saveAndFlush(media);

        // Historical playable asset is still intact
        var playable = revisionRepository.findPlayableAsset(draft.getId());
        assertThat(playable).isPresent();
        assertThat(playable.get().getKey()).isEqualTo("content/" + media.getId() + ".mp3");

        // Revision media ref still exists
        assertThat(revisionMediaRefRepository.findByRevisionId(draft.getId())).hasSize(1);
    }
}
