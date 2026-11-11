package com.masterenglish.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.modules.content.authoring.ContentAuthoringService;
import com.masterenglish.api.modules.content.entity.ContentItem;
import com.masterenglish.api.modules.content.entity.ContentRevision;
import com.masterenglish.api.modules.content.repository.AuditLogRepository;
import com.masterenglish.api.modules.content.repository.ContentItemRepository;
import com.masterenglish.api.modules.content.repository.ContentRevisionRepository;
import com.masterenglish.api.modules.content.repository.RevisionDependencyRepository;
import com.masterenglish.api.modules.vocabulary.entity.Deck;
import com.masterenglish.api.modules.vocabulary.entity.Word;
import com.masterenglish.api.modules.vocabulary.entity.WordSense;
import com.masterenglish.api.modules.vocabulary.repository.DeckRepository;
import com.masterenglish.api.modules.vocabulary.repository.DeckWordRepository;
import com.masterenglish.api.modules.vocabulary.repository.WordRepository;
import com.masterenglish.api.modules.vocabulary.repository.WordSenseRepository;
import com.masterenglish.api.modules.vocabulary.seed.VocabularySeedService;
import com.masterenglish.api.modules.vocabulary.seed.VocabularySeedServiceImpl;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class VocabularyContentAuthoringTests {

    @Autowired private ContentAuthoringService authoringService;
    @Autowired private ContentItemRepository itemRepository;
    @Autowired private ContentRevisionRepository revisionRepository;
    @Autowired private RevisionDependencyRepository dependencyRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private WordRepository wordRepository;
    @Autowired private WordSenseRepository senseRepository;
    @Autowired private DeckRepository deckRepository;
    @Autowired private DeckWordRepository deckWordRepository;
    @Autowired private VocabularySeedService seedService;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    @DisplayName(
            "Content authoring lifecycle DRAFT -> IN_REVIEW -> APPROVED -> PUBLISHED projects atomically")
    void verifyAuthoringLifecycleAndProjection() {
        UUID adminId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, email, password_hash, first_name, last_name, status, email_verified_at) VALUES (?, ?, ?, ?, ?, 'ACTIVE', NOW())",
                adminId,
                "author-admin@example.com",
                "$2a$12$e8YkC6XQ8XbY0u9VqT6eIeaFmP/2eZ.z1c8yU2MvX3mN.rLw5QkOe",
                "Author",
                "Admin");

        jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES (?,2)", adminId);
        var source =
                authoringService.createSource(
                        UUID.randomUUID(),
                        "Test original source",
                        "SELF_AUTHORED",
                        null,
                        "Test author",
                        "SELF_AUTHORED",
                        null,
                        "Test attribution",
                        "Original test content",
                        adminId);
        // 1. Create WORD with draft
        String wordSnapshot =
                """
                {
                  "schema_version": 1,
                  "content_type": "WORD",
                  "lemma": "resilient",
                  "language_code": "en",
                  "ipa": "/rɪˈzɪliənt/",
                  "senses": [
                    {
                      "id": "c2000000-0000-0000-0000-000000000001",
                      "sense_no": 1,
                      "part_of_speech": "ADJECTIVE",
                      "cefr_level": "B2",
                      "meaning_vi": "kiên cường, có khả năng phục hồi nhanh",
                      "definition_en": "able to recover quickly from difficult conditions",
                      "examples": [
                        {
                          "id": "c3000000-0000-0000-0000-000000000001",
                          "position": 1,
                          "example_en": "He is remarkably resilient.",
                          "example_vi": "Anh ấy kiên cường một cách đáng nể."
                        }
                      ]
                    }
                  ]
                }
                """;

        UUID wordItemId = UUID.randomUUID();
        ContentItem wordItem =
                authoringService.createItemWithDraft(
                        wordItemId,
                        "WORD",
                        adminId,
                        "custom_test",
                        "word:resilient",
                        wordSnapshot,
                        "Initial draft for resilient",
                        source.getId());

        List<ContentRevision> revs =
                revisionRepository.findByContentItemIdOrderByVersionNoDesc(wordItem.getId());
        assertThat(revs).hasSize(1);
        ContentRevision draft = revs.get(0);
        assertThat(draft.getStatus()).isEqualTo("DRAFT");

        // 2. Submit for review
        ContentRevision inReview = authoringService.submitForReview(draft.getId(), adminId);
        assertThat(inReview.getStatus()).isEqualTo("IN_REVIEW");

        // 3. Approve
        ContentRevision approved =
                authoringService.approve(inReview.getId(), adminId, "Content verified");
        assertThat(approved.getStatus()).isEqualTo("APPROVED");

        // 4. Publish
        ContentRevision published = authoringService.publish(approved.getId(), adminId);
        assertThat(published.getStatus()).isEqualTo("PUBLISHED");
        assertThat(published.getPublishedAt()).isNotNull();

        // 5. Verify projection in words and word_senses
        Word projectedWord = wordRepository.findById(wordItemId).orElseThrow();
        assertThat(projectedWord.getLemma()).isEqualTo("resilient");
        assertThat(projectedWord.getIpa()).isEqualTo("/rɪˈzɪliənt/");
        assertThat(projectedWord.getPublishedRevisionId()).isEqualTo(published.getId());

        UUID senseId = UUID.fromString("c2000000-0000-0000-0000-000000000001");
        WordSense projectedSense = senseRepository.findById(senseId).orElseThrow();
        assertThat(projectedSense.getPartOfSpeech()).isEqualTo("ADJECTIVE");
        assertThat(projectedSense.getCefrLevel()).isEqualTo("B2");
        assertThat(projectedSense.getMeaningVi())
                .isEqualTo("kiên cường, có khả năng phục hồi nhanh");

        // 6. Immutability check: cannot re-publish an already published revision
        assertThatThrownBy(() -> authoringService.publish(published.getId(), adminId))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    @DisplayName(
            "VocabularySeedService seeds 2 decks, 21 senses, multi-sense words and shared sense idempotently")
    void verifyVocabularySeedService() {
        UUID adminId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, email, password_hash, first_name, last_name, status, email_verified_at) VALUES (?, ?, ?, ?, ?, 'ACTIVE', NOW())",
                adminId,
                "seed-admin@example.com",
                "$2a$12$e8YkC6XQ8XbY0u9VqT6eIeaFmP/2eZ.z1c8yU2MvX3mN.rLw5QkOe",
                "Seed",
                "Admin");

        jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES (?,2)", adminId);
        // First run
        seedService.seedVocabularyData(adminId);

        // Verify Decks
        Deck deck1 = deckRepository.findById(VocabularySeedServiceImpl.DECK_1_ID).orElseThrow();
        assertThat(deck1.getTitle()).isEqualTo("Workplace & Daily Communication");
        assertThat(deck1.getLevel()).isEqualTo("B1");

        Deck deck2 = deckRepository.findById(VocabularySeedServiceImpl.DECK_2_ID).orElseThrow();
        assertThat(deck2.getTitle()).isEqualTo("Academic & Strategic Essentials");
        assertThat(deck2.getLevel()).isEqualTo("B2");

        long deck1Count = deckWordRepository.countByIdDeckId(VocabularySeedServiceImpl.DECK_1_ID);
        long deck2Count = deckWordRepository.countByIdDeckId(VocabularySeedServiceImpl.DECK_2_ID);
        assertThat(deck1Count).isEqualTo(12);
        assertThat(deck2Count).isEqualTo(10);

        // Verify shared sense (approach noun) belongs to both decks
        UUID sharedSenseId = UUID.fromString("20000000-0000-0000-0000-000000000001");
        assertThat(
                        deckWordRepository.existsById(
                                new com.masterenglish.api.modules.vocabulary.entity.DeckWord
                                        .DeckWordId(
                                        VocabularySeedServiceImpl.DECK_1_ID, sharedSenseId)))
                .isTrue();
        assertThat(
                        deckWordRepository.existsById(
                                new com.masterenglish.api.modules.vocabulary.entity.DeckWord
                                        .DeckWordId(
                                        VocabularySeedServiceImpl.DECK_2_ID, sharedSenseId)))
                .isTrue();

        // Verify multi-sense word (schedule has 2 senses)
        UUID scheduleWordId = UUID.fromString("10000000-0000-0000-0000-000000000002");
        List<WordSense> scheduleSenses =
                senseRepository.findByWordIdAndArchivedFalseOrderBySenseNoAsc(scheduleWordId);
        assertThat(scheduleSenses).hasSize(2);
        assertThat(scheduleSenses.get(0).getPartOfSpeech()).isEqualTo("NOUN");
        assertThat(scheduleSenses.get(1).getPartOfSpeech()).isEqualTo("VERB");

        // Verify dependencies recorded for Deck 2
        ContentItem deck2Item =
                itemRepository.findById(VocabularySeedServiceImpl.DECK_2_ID).orElseThrow();
        assertThat(
                        dependencyRepository.findByIdParentRevisionId(
                                deck2Item.getPublishedRevisionId()))
                .isNotEmpty();

        // Second run must be idempotent (no duplicate key or conflict)
        seedService.seedVocabularyData(adminId);

        assertThat(deckWordRepository.countByIdDeckId(VocabularySeedServiceImpl.DECK_1_ID))
                .isEqualTo(12);
        assertThat(deckWordRepository.countByIdDeckId(VocabularySeedServiceImpl.DECK_2_ID))
                .isEqualTo(10);
    }
}
