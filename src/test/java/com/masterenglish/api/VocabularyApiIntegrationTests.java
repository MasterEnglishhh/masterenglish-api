package com.masterenglish.api;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.masterenglish.api.modules.identity.entity.Role;
import com.masterenglish.api.modules.identity.entity.User;
import com.masterenglish.api.modules.identity.repository.UserRepository;
import com.masterenglish.api.modules.identity.security.UserPrincipal;
import com.masterenglish.api.modules.vocabulary.seed.VocabularySeedService;
import com.masterenglish.api.modules.vocabulary.seed.VocabularySeedServiceImpl;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class VocabularyApiIntegrationTests {

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private EntityManager entityManager;
    @Autowired private VocabularySeedService seedService;

    private MockMvc mockMvc;
    private User learnerUser;
    private User adminOnlyUser;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        Role learnerRole = entityManager.find(Role.class, (short) 1);
        Role adminRole = entityManager.find(Role.class, (short) 2);

        learnerUser =
                new User(
                        "learner-api@example.com",
                        passwordEncoder.encode("Password123!@#"),
                        "Learner",
                        "User");
        learnerUser.addRole(learnerRole);
        learnerUser.markEmailVerified(Instant.now());
        learnerUser = userRepository.saveAndFlush(learnerUser);

        adminOnlyUser =
                new User(
                        "admin-only@example.com",
                        passwordEncoder.encode("Password123!@#"),
                        "Admin",
                        "Only");
        adminOnlyUser.addRole(adminRole);
        adminOnlyUser.markEmailVerified(Instant.now());
        adminOnlyUser = userRepository.saveAndFlush(adminOnlyUser);

        // Seed vocabulary data with adminUser
        seedService.seedVocabularyData(adminOnlyUser.getId());
    }

    @Test
    @DisplayName("GET /api/v1/vocabulary/words requires authentication and LEARNER role")
    void verifyWordListSecurity() throws Exception {
        // 1. Unauthenticated -> 401
        mockMvc.perform(get("/api/v1/vocabulary/words")).andExpect(status().isUnauthorized());

        // 2. Authenticated but admin-only without LEARNER -> 403
        mockMvc.perform(
                        get("/api/v1/vocabulary/words")
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                UserPrincipal.create(adminOnlyUser))))
                .andExpect(status().isForbidden());

        // 3. Authenticated as LEARNER -> 200 OK
        mockMvc.perform(
                        get("/api/v1/vocabulary/words")
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                UserPrincipal.create(learnerUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.totalItems").value(org.hamcrest.Matchers.greaterThan(0)));
    }

    @Test
    @DisplayName("Search and filter on words queries correctly by lemma and CEFR level on senses")
    void verifyWordFiltering() throws Exception {
        // Query prefix 'approach'
        mockMvc.perform(
                        get("/api/v1/vocabulary/words")
                                .param("q", "approach")
                                .param("cefrLevel", "B1")
                                .param("partOfSpeech", "NOUN")
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                UserPrincipal.create(learnerUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isNotEmpty())
                .andExpect(jsonPath("$.items[0].lemma").value("approach"))
                .andExpect(jsonPath("$.items[0].senses[0].partOfSpeech").value("NOUN"))
                .andExpect(jsonPath("$.items[0].senses[0].cefrLevel").value("B1"));
    }

    @Test
    @DisplayName("GET /api/v1/vocabulary/words/{wordId} returns full senses and examples")
    void verifyWordDetail() throws Exception {
        UUID approachWordId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        mockMvc.perform(
                        get("/api/v1/vocabulary/words/{wordId}", approachWordId)
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                UserPrincipal.create(learnerUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(approachWordId.toString()))
                .andExpect(jsonPath("$.lemma").value("approach"))
                .andExpect(jsonPath("$.senses").hasJsonPath())
                .andExpect(jsonPath("$.senses[0].examples").isArray())
                .andExpect(jsonPath("$.senses[0].examples[0].exampleEn").isNotEmpty());
    }

    @Test
    @DisplayName(
            "GET /api/v1/vocabulary/decks and decks/{deckId} return published decks with card"
                    + " counts")
    void verifyDecks() throws Exception {
        mockMvc.perform(
                        get("/api/v1/vocabulary/decks")
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                UserPrincipal.create(learnerUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(
                        jsonPath("$.items[0].cardCount")
                                .value(org.hamcrest.Matchers.greaterThan(0)));

        mockMvc.perform(
                        get(
                                        "/api/v1/vocabulary/decks/{deckId}",
                                        VocabularySeedServiceImpl.DECK_1_ID)
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                UserPrincipal.create(learnerUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(VocabularySeedServiceImpl.DECK_1_ID.toString()))
                .andExpect(jsonPath("$.cardCount").value(12));
    }

    @Test
    @DisplayName("GET /api/v1/vocabulary/decks/{deckId}/words returns flashcards in position order")
    void verifyDeckCards() throws Exception {
        mockMvc.perform(
                        get(
                                        "/api/v1/vocabulary/decks/{deckId}/words",
                                        VocabularySeedServiceImpl.DECK_1_ID)
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                UserPrincipal.create(learnerUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(12))
                .andExpect(jsonPath("$.items[0].position").value(1))
                .andExpect(jsonPath("$.items[0].lemma").value("approach"))
                .andExpect(jsonPath("$.items[0].isSaved").value(false));
    }

    @Test
    @DisplayName("Saved words lifecycle PUT -> GET -> DELETE is idempotent and preserves SRS")
    void verifySavedWordsLifecycle() throws Exception {
        UUID senseId = UUID.fromString("20000000-0000-0000-0000-000000000001");

        // 1. Initially saved-words is empty
        mockMvc.perform(
                        get("/api/v1/vocabulary/saved-words")
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                UserPrincipal.create(learnerUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(0));

        // 2. PUT /saved-words/{senseId} -> 204 No Content
        mockMvc.perform(
                        put("/api/v1/vocabulary/saved-words/{senseId}", senseId)
                                .with(SecurityMockMvcRequestPostProcessors.csrf())
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                UserPrincipal.create(learnerUser))))
                .andExpect(status().isNoContent());

        // 3. GET /saved-words -> 200 OK has 1 item
        mockMvc.perform(
                        get("/api/v1/vocabulary/saved-words")
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                UserPrincipal.create(learnerUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].wordSenseId").value(senseId.toString()));

        mockMvc.perform(
                        get("/api/v1/vocabulary/words/10000000-0000-0000-0000-000000000001/saved-senses")
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                UserPrincipal.create(learnerUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0]").value(senseId.toString()));

        // 4. Deck words now indicates isSaved = true
        mockMvc.perform(
                        get(
                                        "/api/v1/vocabulary/decks/{deckId}/words",
                                        VocabularySeedServiceImpl.DECK_1_ID)
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                UserPrincipal.create(learnerUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].isSaved").value(true));

        // 5. DELETE /saved-words/{senseId} -> 204 No Content
        mockMvc.perform(
                        delete("/api/v1/vocabulary/saved-words/{senseId}", senseId)
                                .with(SecurityMockMvcRequestPostProcessors.csrf())
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                UserPrincipal.create(learnerUser))))
                .andExpect(status().isNoContent());

        // 6. GET /saved-words is empty again
        mockMvc.perform(
                        get("/api/v1/vocabulary/saved-words")
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                UserPrincipal.create(learnerUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    void savedSenseMembershipIsScopedToTheCurrentLearner() throws Exception {
        UUID senseId = UUID.fromString("20000000-0000-0000-0000-000000000001");
        mockMvc.perform(
                        put("/api/v1/vocabulary/saved-words/{senseId}", senseId)
                                .with(SecurityMockMvcRequestPostProcessors.csrf())
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                UserPrincipal.create(learnerUser))))
                .andExpect(status().isNoContent());
        User other =
                new User(
                        "other-audio-learner@example.com",
                        passwordEncoder.encode("Password123!@#"),
                        "Other",
                        "Learner");
        other.addRole(entityManager.find(Role.class, (short) 1));
        other.markEmailVerified(Instant.now());
        other = userRepository.saveAndFlush(other);
        mockMvc.perform(
                        get("/api/v1/vocabulary/words/10000000-0000-0000-0000-000000000001/saved-senses")
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                UserPrincipal.create(other))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName(
            "GET /api/v1/vocabulary/reviews/due returns 0 due when no reviews have been submitted")
    void verifyInitialDueQueue() throws Exception {
        mockMvc.perform(
                        get("/api/v1/vocabulary/reviews/due")
                                .with(
                                        SecurityMockMvcRequestPostProcessors.user(
                                                UserPrincipal.create(learnerUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalDue").value(0))
                .andExpect(jsonPath("$.cards").isEmpty());
    }
}
