package com.masterenglish.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.masterenglish.api.modules.identity.entity.Role;
import com.masterenglish.api.modules.identity.entity.User;
import com.masterenglish.api.modules.identity.repository.UserRepository;
import com.masterenglish.api.modules.identity.security.UserPrincipal;
import com.masterenglish.api.modules.learning.dto.ReviewRequest;
import com.masterenglish.api.modules.learning.repository.UserWordProgressRepository;
import com.masterenglish.api.modules.learning.repository.WordReviewRepository;
import com.masterenglish.api.modules.vocabulary.query.VocabularyQueryService;
import com.masterenglish.api.modules.vocabulary.query.WordSenseCardDto;
import com.masterenglish.api.modules.vocabulary.seed.VocabularySeedService;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class VocabularyReviewIntegrationTests {

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private EntityManager entityManager;
    @Autowired private VocabularySeedService seedService;
    @Autowired private VocabularyQueryService vocabularyQueryService;
    @Autowired private UserWordProgressRepository progressRepository;
    @Autowired private WordReviewRepository reviewRepository;
    @Autowired private ObjectMapper objectMapper;

    private MockMvc mockMvc;
    private User learner1;
    private User learner2;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        Role learnerRole = entityManager.find(Role.class, (short) 1);
        Role adminRole = entityManager.find(Role.class, (short) 2);

        learner1 =
                new User(
                        "learner1-review@example.com",
                        passwordEncoder.encode("Password123!@#"),
                        "Learner",
                        "One");
        learner1.addRole(learnerRole);
        learner1.markEmailVerified(Instant.now());
        learner1 = userRepository.saveAndFlush(learner1);

        learner2 =
                new User(
                        "learner2-review@example.com",
                        passwordEncoder.encode("Password123!@#"),
                        "Learner",
                        "Two");
        learner2.addRole(learnerRole);
        learner2.markEmailVerified(Instant.now());
        learner2 = userRepository.saveAndFlush(learner2);

        User admin =
                new User(
                        "admin-review@example.com",
                        passwordEncoder.encode("Password123!@#"),
                        "Admin",
                        "Review");
        admin.addRole(adminRole);
        admin.markEmailVerified(Instant.now());
        admin = userRepository.saveAndFlush(admin);

        seedService.seedVocabularyData(admin.getId());
    }

    @Test
    @DisplayName(
            "Review flow: first review rated GOOD creates progress and review event atomically")
    void verifyInitialReview() throws Exception {
        UUID senseId = UUID.fromString("20000000-0000-0000-0000-000000000001");
        WordSenseCardDto card = vocabularyQueryService.getCardBySenseId(senseId).orElseThrow();
        UUID requestId = UUID.randomUUID();

        ReviewRequest request =
                new ReviewRequest(
                        senseId,
                        card.wordId(),
                        card.wordRevisionId(),
                        null,
                        null,
                        "GOOD",
                        requestId,
                        null);

        mockMvc.perform(
                        post("/api/v1/vocabulary/reviews")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request))
                                .with(SecurityMockMvcRequestPostProcessors.csrf())
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                UserPrincipal.create(learner1))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reviewId").value(requestId.toString()))
                .andExpect(jsonPath("$.wordSenseId").value(senseId.toString()))
                .andExpect(jsonPath("$.rating").value("GOOD"))
                .andExpect(jsonPath("$.repetitions").value(1))
                .andExpect(jsonPath("$.intervalDays").value(1))
                .andExpect(jsonPath("$.easeFactor").value(2.600))
                .andExpect(jsonPath("$.nextDueAt").isNotEmpty())
                .andExpect(jsonPath("$.progressVersion").value(0));

        assertThat(progressRepository.findByIdUserIdAndIdWordSenseId(learner1.getId(), senseId))
                .isPresent();
        assertThat(reviewRepository.findByUserIdAndRequestId(learner1.getId(), requestId))
                .isPresent();
    }

    @Test
    @DisplayName(
            "Idempotent retry: resending the same requestId with same payload returns identical result")
    void verifyIdempotentRetry() throws Exception {
        UUID senseId = UUID.fromString("20000000-0000-0000-0000-000000000001");
        WordSenseCardDto card = vocabularyQueryService.getCardBySenseId(senseId).orElseThrow();
        UUID requestId = UUID.randomUUID();

        ReviewRequest request =
                new ReviewRequest(
                        senseId,
                        card.wordId(),
                        card.wordRevisionId(),
                        null,
                        null,
                        "GOOD",
                        requestId,
                        null);

        // 1. First attempt
        mockMvc.perform(
                        post("/api/v1/vocabulary/reviews")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request))
                                .with(SecurityMockMvcRequestPostProcessors.csrf())
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                UserPrincipal.create(learner1))))
                .andExpect(status().isCreated());

        long reviewCountBefore = reviewRepository.count();

        // 2. Retry with exact same request
        mockMvc.perform(
                        post("/api/v1/vocabulary/reviews")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request))
                                .with(SecurityMockMvcRequestPostProcessors.csrf())
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                UserPrincipal.create(learner1))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reviewId").value(requestId.toString()))
                .andExpect(jsonPath("$.rating").value("GOOD"));

        // No new review row created
        assertThat(reviewRepository.count()).isEqualTo(reviewCountBefore);
    }

    @Test
    @DisplayName("Conflict on retry: reusing requestId with different rating throws 409 Conflict")
    void verifyDuplicateRequestIdConflict() throws Exception {
        UUID senseId = UUID.fromString("20000000-0000-0000-0000-000000000001");
        WordSenseCardDto card = vocabularyQueryService.getCardBySenseId(senseId).orElseThrow();
        UUID requestId = UUID.randomUUID();

        ReviewRequest request1 =
                new ReviewRequest(
                        senseId,
                        card.wordId(),
                        card.wordRevisionId(),
                        null,
                        null,
                        "GOOD",
                        requestId,
                        null);

        mockMvc.perform(
                        post("/api/v1/vocabulary/reviews")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request1))
                                .with(SecurityMockMvcRequestPostProcessors.csrf())
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                UserPrincipal.create(learner1))))
                .andExpect(status().isCreated());

        // Same requestId, different rating "HARD"
        ReviewRequest request2 =
                new ReviewRequest(
                        senseId,
                        card.wordId(),
                        card.wordRevisionId(),
                        null,
                        null,
                        "HARD",
                        requestId,
                        null);

        mockMvc.perform(
                        post("/api/v1/vocabulary/reviews")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request2))
                                .with(SecurityMockMvcRequestPostProcessors.csrf())
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                UserPrincipal.create(learner1))))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Stale progress version throws 409 Conflict")
    void verifyStaleVersionConflict() throws Exception {
        UUID senseId = UUID.fromString("20000000-0000-0000-0000-000000000001");
        WordSenseCardDto card = vocabularyQueryService.getCardBySenseId(senseId).orElseThrow();

        // Expect version 1 when progress does not exist yet (expected is null)
        ReviewRequest staleRequest =
                new ReviewRequest(
                        senseId,
                        card.wordId(),
                        card.wordRevisionId(),
                        null,
                        null,
                        "GOOD",
                        UUID.randomUUID(),
                        1L);

        mockMvc.perform(
                        post("/api/v1/vocabulary/reviews")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(staleRequest))
                                .with(SecurityMockMvcRequestPostProcessors.csrf())
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                UserPrincipal.create(learner1))))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Rating AGAIN resets repetitions=0, interval=0, sets due=now+10min")
    void verifyAgainRating() throws Exception {
        UUID senseId = UUID.fromString("20000000-0000-0000-0000-000000000001");
        WordSenseCardDto card = vocabularyQueryService.getCardBySenseId(senseId).orElseThrow();

        ReviewRequest request =
                new ReviewRequest(
                        senseId,
                        card.wordId(),
                        card.wordRevisionId(),
                        null,
                        null,
                        "AGAIN",
                        UUID.randomUUID(),
                        null);

        mockMvc.perform(
                        post("/api/v1/vocabulary/reviews")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request))
                                .with(SecurityMockMvcRequestPostProcessors.csrf())
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                UserPrincipal.create(learner1))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.repetitions").value(0))
                .andExpect(jsonPath("$.intervalDays").value(0))
                .andExpect(jsonPath("$.easeFactor").value(1.960));
    }

    @Test
    @DisplayName(
            "User isolation: Learner 1 review does not leak into Learner 2 due queue or progress")
    void verifyUserIsolation() throws Exception {
        UUID senseId = UUID.fromString("20000000-0000-0000-0000-000000000001");
        WordSenseCardDto card = vocabularyQueryService.getCardBySenseId(senseId).orElseThrow();

        ReviewRequest request =
                new ReviewRequest(
                        senseId,
                        card.wordId(),
                        card.wordRevisionId(),
                        null,
                        null,
                        "GOOD",
                        UUID.randomUUID(),
                        null);

        // Learner 1 reviews
        mockMvc.perform(
                        post("/api/v1/vocabulary/reviews")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request))
                                .with(SecurityMockMvcRequestPostProcessors.csrf())
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                UserPrincipal.create(learner1))))
                .andExpect(status().isCreated());

        // Learner 2 due queue is empty
        mockMvc.perform(
                        get("/api/v1/vocabulary/reviews/due")
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                UserPrincipal.create(learner2))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalDue").value(0))
                .andExpect(jsonPath("$.cards").isEmpty());

        // Learner 2 progress for senseId is not present
        assertThat(progressRepository.findByIdUserIdAndIdWordSenseId(learner2.getId(), senseId))
                .isEmpty();
    }
}
