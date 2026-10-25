package com.masterenglish.api.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.masterenglish.api.common.error.RequestIdFilter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import tools.jackson.databind.json.JsonMapper;

class SecurityProblemHandlerTests {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private final SecurityProblemHandler handler = new SecurityProblemHandler(MAPPER);

    @Test
    void authenticationFailureUsesProblemJsonWithoutExposingInternalDetails() throws Exception {
        var request = request();
        var response = new MockHttpServletResponse();
        handler.commence(request, response, new BadCredentialsException("private-auth-detail"));

        assertProblem(response, 401, "UNAUTHENTICATED");
        assertThat(response.getContentAsString())
                .doesNotContain("private-auth-detail", "private-query-value");
    }

    @Test
    void accessDenialUsesProblemJsonWithoutExposingThePolicy() throws Exception {
        var request = request();
        var response = new MockHttpServletResponse();
        handler.handle(request, response, new AccessDeniedException("private-policy-detail"));

        assertProblem(response, 403, "ACCESS_DENIED");
        assertThat(response.getContentAsString())
                .doesNotContain("private-policy-detail", "private-query-value");
    }

    @Test
    void fallbackTraceIdMatchesTheResponseHeader() throws Exception {
        var request = request();
        request.removeAttribute(RequestIdFilter.ATTRIBUTE);
        var response = new MockHttpServletResponse();
        handler.commence(request, response, new BadCredentialsException("not public"));

        String traceId = response.getHeader(RequestIdFilter.HEADER);
        assertThat(UUID.fromString(traceId)).isNotNull();
        assertThat(problem(response).get("traceId")).isEqualTo(traceId);
    }

    @Test
    void committedResponseIsNotOverwritten() throws Exception {
        var response = new MockHttpServletResponse();
        response.setStatus(202);
        response.getWriter().write("already finished");
        response.flushBuffer();

        handler.handle(request(), response, new AccessDeniedException("not public"));

        assertThat(response.getStatus()).isEqualTo(202);
        assertThat(response.getContentAsString()).isEqualTo("already finished");
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isNull();
    }

    private static MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("GET", "/api/private");
        request.setAttribute(RequestIdFilter.ATTRIBUTE, "security-test-123");
        request.setQueryString("sensitive=private-query-value");
        return request;
    }

    private static void assertProblem(MockHttpServletResponse response, int status, String code) {
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.getCharacterEncoding()).isEqualToIgnoringCase("UTF-8");
        Map<?, ?> problem = problem(response);
        assertThat(problem.get("status")).isEqualTo(status);
        assertThat(problem.get("code")).isEqualTo(code);
        assertThat(problem.get("instance")).isEqualTo("/api/private");
        assertThat(problem.get("traceId")).isEqualTo("security-test-123");
        assertThat(problem.get("errors")).isEqualTo(List.of());
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("security-test-123");
    }

    private static Map<?, ?> problem(MockHttpServletResponse response) {
        return MAPPER.readValue(response.getContentAsByteArray(), Map.class);
    }
}
