package com.masterenglish.api.common.error;

import com.masterenglish.api.common.api.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ErrorHandlingTests {

    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new RequestIdFilter()).build();
    }

    @Test
    void returnsProblemDetailsWithMatchingRequestId() throws Exception {
        mvc.perform(get("/base-test/business").header(RequestIdFilter.HEADER, "request-123"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(RequestIdFilter.HEADER, "request-123"))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.traceId").value("request-123"))
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void validationDoesNotExposeTheRejectedPassword() throws Exception {
        mvc.perform(post("/base-test/validate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"short-secret\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("password"))
                .andExpect(content().string(not(containsString("short-secret"))));
    }

    @Test
    void malformedJsonDoesNotEchoRequestContents() throws Exception {
        mvc.perform(post("/base-test/validate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"never-echo-this\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(content().string(not(containsString("never-echo-this"))));
    }

    @Test
    void unexpectedExceptionsDoNotExposeInternalMessages() throws Exception {
        mvc.perform(get("/base-test/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(not(containsString("do-not-expose"))));
    }

    @Test
    void replacesInvalidIncomingRequestIds() throws Exception {
        var response = mvc.perform(get("/base-test/business")
                        .header(RequestIdFilter.HEADER, "invalid request id !")).andReturn().getResponse();
        assertThat(UUID.fromString(response.getHeader(RequestIdFilter.HEADER))).isNotNull();
    }

    @Test
    void pageResponseMatchesTheApiContract() throws Exception {
        mvc.perform(get("/base-test/page")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0]").value("first"))
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.content").doesNotExist())
                .andExpect(jsonPath("$.success").doesNotExist());
    }

    @Test
    void authorizationFailuresInsideMvcRemainForbidden() throws Exception {
        mvc.perform(get("/base-test/forbidden")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void authenticationFailuresInsideMvcRemainUnauthorized() throws Exception {
        mvc.perform(get("/base-test/unauthenticated")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Profile("base-unit-fixture")
    @RestController
    static class ProbeController {
        @GetMapping("/base-test/forbidden")
        String forbidden() {
            throw new AccessDeniedException("internal policy reason");
        }

        @GetMapping("/base-test/unauthenticated")
        String unauthenticated() {
            throw new BadCredentialsException("internal authentication reason");
        }

        @GetMapping("/base-test/business")
        String business() {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Resource not found.");
        }

        @PostMapping("/base-test/validate")
        Input validate(@Valid @RequestBody Input input) {
            return input;
        }

        @GetMapping("/base-test/unexpected")
        String unexpected() {
            throw new IllegalArgumentException("password=do-not-expose");
        }

        @GetMapping("/base-test/page")
        PageResponse<String> page() {
            return PageResponse.from(new PageImpl<>(List.of("first"), PageRequest.of(0, 1), 2));
        }
    }

    record Input(@Size(min = 20) String password) { }
}
