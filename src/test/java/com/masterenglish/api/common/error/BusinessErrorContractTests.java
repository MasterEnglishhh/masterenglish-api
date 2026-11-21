package com.masterenglish.api.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Contract tests for BusinessException details (practice/CONTRACT.md section 4.5). */
class BusinessErrorContractTests {

    private static final Set<String> PROBLEM_MEMBERS =
            Set.of("type", "title", "status", "detail", "instance", "code", "traceId", "errors");
    private static final String UUID_PATTERN =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc =
                MockMvcBuilders.standaloneSetup(new DetailProbeController())
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .addFilters(new RequestIdFilter())
                        .build();
    }

    @Test
    void legacyConstructorKeepsCodeMessageAndNoDetails() {
        BusinessException exception =
                new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Resource not found.");

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(exception.getMessage()).isEqualTo("Resource not found.");
        assertThat(exception.getDetails()).isEmpty();
    }

    @Test
    void errorCodeNamesAndStatusesAreUnchanged() {
        assertThat(
                        Arrays.stream(ErrorCode.values())
                                .map(code -> code.name() + ":" + code.getHttpStatus().value())
                                .toList())
                .containsExactly(
                        "VALIDATION_ERROR:400",
                        "UNAUTHENTICATED:401",
                        "ACCESS_DENIED:403",
                        "RESOURCE_NOT_FOUND:404",
                        "CONFLICT:409",
                        "PAYLOAD_TOO_LARGE:413",
                        "RATE_LIMITED:429",
                        "OTP_INVALID:400",
                        "OTP_EXPIRED:400",
                        "REGISTRATION_EXPIRED:400",
                        "PASSWORD_REJECTED:400",
                        "RESET_TOKEN_INVALID:400",
                        "SERVICE_UNAVAILABLE:503",
                        "INTERNAL_ERROR:500",
                        "NO_ELIGIBLE_QUESTIONS:409",
                        "INSUFFICIENT_QUESTIONS:409");
    }

    @Test
    void onlyCountKeysAreAllowlistedForQuestionShortage() {
        assertThat(ErrorCode.INSUFFICIENT_QUESTIONS.allowsDetail("requestedCount")).isTrue();
        assertThat(ErrorCode.INSUFFICIENT_QUESTIONS.allowsDetail("availableCount")).isTrue();
        assertThat(ErrorCode.INSUFFICIENT_QUESTIONS.allowsDetail("sqlState")).isFalse();
        assertThat(ErrorCode.INSUFFICIENT_QUESTIONS.allowsDetail(null)).isFalse();
        assertThat(ErrorCode.CONFLICT.allowsDetail("requestedCount")).isFalse();
    }

    @Test
    void reservedProblemMembersAreNeverAllowlisted() {
        for (ErrorCode code : ErrorCode.values()) {
            for (String member : PROBLEM_MEMBERS) {
                assertThat(code.allowsDetail(member)).isFalse();
            }
        }
    }

    @Test
    void rejectsKeysOutsideTheAllowlist() {
        assertThatThrownBy(
                        () ->
                                new BusinessException(
                                        ErrorCode.INSUFFICIENT_QUESTIONS,
                                        "Not enough eligible questions.",
                                        Map.of("sqlState", 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsValuesThatAreNotNonNegativeCounts() {
        assertThatThrownBy(
                        () ->
                                new BusinessException(
                                        ErrorCode.INSUFFICIENT_QUESTIONS,
                                        "m",
                                        Map.of("requestedCount", "20")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new BusinessException(
                                        ErrorCode.INSUFFICIENT_QUESTIONS,
                                        "m",
                                        Map.of("requestedCount", -1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new BusinessException(
                                        ErrorCode.INSUFFICIENT_QUESTIONS,
                                        "m",
                                        Collections.singletonMap("requestedCount", null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsIntegerAndLongCounts() {
        BusinessException exception =
                new BusinessException(
                        ErrorCode.INSUFFICIENT_QUESTIONS,
                        "m",
                        Map.of("requestedCount", 0, "availableCount", 0L));

        assertThat(exception.getDetails())
                .containsEntry("requestedCount", 0)
                .containsEntry("availableCount", 0L);
    }

    @Test
    void detailsAreCopiedAndImmutable() {
        Map<String, Object> source = new HashMap<>(Map.of("requestedCount", 3));
        BusinessException exception =
                new BusinessException(ErrorCode.INSUFFICIENT_QUESTIONS, "m", source);

        source.put("availableCount", 1);

        assertThat(exception.getDetails()).containsOnlyKeys("requestedCount");
        assertThatThrownBy(() -> exception.getDetails().put("availableCount", 1))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void insufficientQuestionsReturnsConflictWithCountsAndTraceId() throws Exception {
        mvc.perform(get("/details-test/insufficient").header("X-Request-Id", "req-insufficient-1"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_QUESTIONS"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value("Not enough eligible questions."))
                .andExpect(jsonPath("$.requestedCount").value(20))
                .andExpect(jsonPath("$.availableCount").value(12))
                .andExpect(jsonPath("$.traceId").value("req-insufficient-1"))
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    @Test
    void noEligibleQuestionsReturnsConflictWithZeroAvailable() throws Exception {
        mvc.perform(get("/details-test/none"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_ELIGIBLE_QUESTIONS"))
                .andExpect(jsonPath("$.requestedCount").value(5))
                .andExpect(jsonPath("$.availableCount").value(0))
                .andExpect(jsonPath("$.traceId", matchesPattern(UUID_PATTERN)));
    }

    @Test
    void businessExceptionWithoutDetailsAddsNoExtraMembers() throws Exception {
        String body =
                mvc.perform(get("/details-test/legacy"))
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value("CONFLICT"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(fieldNames(body))
                .contains("code", "traceId", "errors")
                .isSubsetOf(PROBLEM_MEMBERS);
    }

    @Test
    void rejectedDetailKeyNeverReachesTheClient() throws Exception {
        mvc.perform(get("/details-test/rejected-key"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                .andExpect(jsonPath("$.requestedCount").doesNotExist())
                .andExpect(content().string(not(containsString("sqlState"))))
                .andExpect(content().string(not(containsString("42P01"))))
                .andExpect(content().string(not(containsString("internal-table"))));
    }

    @Test
    void errorWithoutRequestIdHeaderStillCarriesTraceId() throws Exception {
        mvc.perform(get("/details-test/insufficient"))
                .andExpect(jsonPath("$.traceId", matchesPattern(UUID_PATTERN)));
    }

    private static Set<String> fieldNames(String json) throws Exception {
        Set<String> names = new HashSet<>();
        new ObjectMapper().readTree(json).fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Profile("base-unit-fixture")
    @RestController
    static class DetailProbeController {

        @GetMapping("/details-test/insufficient")
        String insufficient() {
            throw new BusinessException(
                    ErrorCode.INSUFFICIENT_QUESTIONS,
                    "Not enough eligible questions.",
                    Map.of("requestedCount", 20, "availableCount", 12));
        }

        @GetMapping("/details-test/none")
        String none() {
            throw new BusinessException(
                    ErrorCode.NO_ELIGIBLE_QUESTIONS,
                    "No eligible questions.",
                    Map.of("requestedCount", 5, "availableCount", 0));
        }

        @GetMapping("/details-test/legacy")
        String legacy() {
            throw new BusinessException(ErrorCode.CONFLICT, "Conflict.");
        }

        @GetMapping("/details-test/rejected-key")
        String rejectedKey() {
            // The constructor rejects this key, so the request must take the generic 500 path.
            throw new BusinessException(
                    ErrorCode.INSUFFICIENT_QUESTIONS,
                    "Not enough eligible questions.",
                    Map.of("sqlState", "42P01-internal-table"));
        }
    }
}
