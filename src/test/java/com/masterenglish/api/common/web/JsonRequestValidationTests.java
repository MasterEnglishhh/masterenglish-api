package com.masterenglish.api.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.masterenglish.api.common.error.GlobalExceptionHandler;
import com.masterenglish.api.common.error.RequestIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Tests the real application JSON settings without a database or login service. */
class JsonRequestValidationTests {
    private static final int LIMIT = 1024 * 1024;
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final FilterChain MUST_NOT_REACH_CONTROLLER =
            (request, response) -> {
                throw new AssertionError("Oversized JSON reached the controller.");
            };

    @Test
    void declaredOversizeIsRejectedBeforeReadingInput() throws Exception {
        var request =
                new MockHttpServletRequest("POST", "/json-probe/input") {
                    @Override
                    public long getContentLengthLong() {
                        return LIMIT + 1L;
                    }

                    @Override
                    public ServletInputStream getInputStream() {
                        throw new AssertionError("An already oversized body must not be read.");
                    }
                };
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        assertRejected(filter(request, MUST_NOT_REACH_CONTROLLER));
    }

    @Test
    void bodyExactlyOneMiBRemainsReadable() throws Exception {
        byte[] body = jsonBody(LIMIT);
        var request = request(body);
        AtomicBoolean reached = new AtomicBoolean();
        var response =
                filter(
                        request,
                        (forwarded, ignored) -> {
                            reached.set(true);
                            assertThat(forwarded.getInputStream().readAllBytes()).isEqualTo(body);
                        });
        assertThat(reached).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void missingContentLengthCannotBypassActualByteLimit() throws Exception {
        assertRejected(
                filter(withLength(request(jsonBody(LIMIT + 1)), -1), MUST_NOT_REACH_CONTROLLER));
    }

    @Test
    void misleadingSmallContentLengthCannotBypassActualByteLimit() throws Exception {
        assertRejected(
                filter(withLength(request(jsonBody(LIMIT + 1)), 8), MUST_NOT_REACH_CONTROLLER));
    }

    @Test
    void utf8BytesAreCountedRatherThanJavaCharacters() throws Exception {
        String body = "\"" + "ệ".repeat(350_000) + "\"";
        assertThat(body.length()).isLessThan(LIMIT);
        assertThat(body.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(LIMIT);
        assertRejected(
                filter(request(body.getBytes(StandardCharsets.UTF_8)), MUST_NOT_REACH_CONTROLLER));
    }

    @Test
    void smallUnknownLengthBodyPreservesVietnameseForReader() throws Exception {
        String body = "{\"name\":\"Nguyễn Huệ\"}";
        var response =
                filter(
                        withLength(request(body.getBytes(StandardCharsets.UTF_8)), -1),
                        (forwarded, ignored) ->
                                assertThat(forwarded.getReader().readLine()).isEqualTo(body));
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void structuredJsonMediaTypesHaveTheSameLimit() throws Exception {
        var request = request(jsonBody(LIMIT + 1));
        request.setContentType("application/vnd.masterenglish+json; charset=UTF-8");
        assertRejected(filter(request, MUST_NOT_REACH_CONTROLLER));
    }

    @Test
    void multipartUploadsAreLeftForTheirOwnByteBudgets() throws Exception {
        var request =
                new MockHttpServletRequest("POST", "/json-probe/upload") {
                    @Override
                    public long getContentLengthLong() {
                        return 2L * LIMIT;
                    }

                    @Override
                    public ServletInputStream getInputStream() {
                        throw new AssertionError("The JSON filter must not consume an upload.");
                    }
                };
        request.setContentType("multipart/form-data; boundary=fixture");
        AtomicBoolean reached = new AtomicBoolean();
        var response =
                filter(
                        request,
                        (forwarded, ignored) -> {
                            assertThat(forwarded).isSameAs(request);
                            reached.set(true);
                        });
        assertThat(reached).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void validDtoStillDeserializesAfterTheFilter() {
        withMvc(
                mvc ->
                        mvc.perform(
                                        post("/json-probe/input")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content("{\"name\":\"Nguyễn Huệ\"}"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.name").value("Nguyễn Huệ")));
    }

    @Test
    void applicationSettingsRejectUnknownFieldsWithoutEchoingValues() {
        withMvc(
                mvc ->
                        mvc.perform(
                                        post("/json-probe/input")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        "{\"name\":\"Test\",\"role\":\"injected-role-secret\"}"))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                                .andExpect(
                                        content()
                                                .string(
                                                        not(
                                                                containsString(
                                                                        "injected-role-secret")))));
    }

    @Test
    void sixteenNestedContainersAreAccepted() {
        withMvc(
                mvc ->
                        mvc.perform(
                                        post("/json-probe/tree")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content("[".repeat(16) + "0" + "]".repeat(16)))
                                .andExpect(status().isOk()));
    }

    @Test
    void seventeenthNestedContainerIsRejected() {
        withMvc(
                mvc ->
                        mvc.perform(
                                        post("/json-probe/tree")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content("[".repeat(17) + "0" + "]".repeat(17)))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR")));
    }

    @Test
    void oversizedMvcRequestReturnsProblemJsonWithMatchingTraceId() {
        withMvc(
                mvc ->
                        mvc.perform(
                                        post("/json-probe/input")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .header(RequestIdFilter.HEADER, "json-size-check")
                                                .content(jsonBody(LIMIT + 1)))
                                .andExpect(status().isContentTooLarge())
                                .andExpect(
                                        content()
                                                .contentTypeCompatibleWith(
                                                        MediaType.APPLICATION_PROBLEM_JSON))
                                .andExpect(
                                        header().string(RequestIdFilter.HEADER, "json-size-check"))
                                .andExpect(jsonPath("$.traceId").value("json-size-check"))
                                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"))
                                .andExpect(jsonPath("$.errors").isArray()));
    }

    private static MockHttpServletRequest request(byte[] body) {
        var request = new MockHttpServletRequest("POST", "/json-probe/input");
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(body);
        return request;
    }

    private static byte[] jsonBody(int bytes) {
        return ("{\"value\":\"" + "a".repeat(bytes - 12) + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    private static HttpServletRequest withLength(HttpServletRequest request, long length) {
        return new HttpServletRequestWrapper(request) {
            @Override
            public long getContentLengthLong() {
                return length;
            }

            @Override
            public int getContentLength() {
                return (int) length;
            }
        };
    }

    private static MockHttpServletResponse filter(HttpServletRequest request, FilterChain next)
            throws ServletException, IOException {
        var response = new MockHttpServletResponse();
        new RequestIdFilter()
                .doFilter(
                        request,
                        response,
                        (forwarded, result) ->
                                new JsonRequestSizeFilter(MAPPER)
                                        .doFilter(forwarded, result, next));
        return response;
    }

    private static void assertRejected(MockHttpServletResponse response) {
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        Map<?, ?> problem = MAPPER.readValue(response.getContentAsByteArray(), Map.class);
        assertThat(problem.get("code")).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(problem.get("traceId")).isEqualTo(response.getHeader(RequestIdFilter.HEADER));
    }

    private static void withMvc(MvcCheck check) {
        // Load the actual application.properties without starting JPA or connecting to a database.
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            var mapper = context.getBean(JsonMapper.class);
                            MockMvc mvc =
                                    MockMvcBuilders.standaloneSetup(new ProbeController())
                                            .setControllerAdvice(new GlobalExceptionHandler())
                                            .setMessageConverters(
                                                    new JacksonJsonHttpMessageConverter(mapper))
                                            .addFilters(
                                                    new RequestIdFilter(),
                                                    new JsonRequestSizeFilter(mapper))
                                            .build();
                            check.accept(mvc);
                        });
    }

    @FunctionalInterface
    private interface MvcCheck {
        void accept(MockMvc mvc) throws Exception;
    }

    @Profile("json-validation-fixture")
    @RestController
    static class ProbeController {
        @PostMapping("/json-probe/input")
        Input input(@RequestBody Input input) {
            return input;
        }

        @PostMapping("/json-probe/tree")
        Map<String, Boolean> tree(@RequestBody JsonNode input) {
            return Map.of("accepted", true);
        }
    }

    record Input(String name) {}
}
