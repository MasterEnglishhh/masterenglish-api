package com.masterenglish.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.masterenglish.api.common.error.GlobalExceptionHandler;
import com.masterenglish.api.common.error.RequestIdFilter;
import com.masterenglish.api.common.security.SecurityProblemHandler;
import com.masterenglish.api.common.web.CsrfController;
import com.masterenglish.api.common.web.JsonRequestSizeFilter;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.json.JsonMapper;

/** Real Spring Security/MVC chain; test principals, no database and no real JWT issuer. */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(
        classes = SecurityConfigurationTests.TestApplication.class,
        initializers = ConfigDataApplicationContextInitializer.class)
@ActiveProfiles("security-probe-fixture")
@TestPropertySource(
        properties = {
            "app.security.allowed-origins=http://localhost:3000",
            "app.security.cookie-secure=false"
        })
class SecurityConfigurationTests {
    @Autowired WebApplicationContext context;
    @Autowired JsonMapper mapper;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc =
                MockMvcBuilders.webAppContextSetup(context)
                        .addFilters(new RequestIdFilter(), new JsonRequestSizeFilter(mapper))
                        .apply(springSecurity())
                        .build();
    }

    @Test
    void anonymousClientCanBootstrapMatchingRawTokenAndCookieWithoutSession() throws Exception {
        var result =
                mvc.perform(get("/api/v1/auth/csrf"))
                        .andExpect(status().isOk())
                        .andExpect(
                                header().string(
                                                HttpHeaders.CACHE_CONTROL,
                                                containsString("no-store")))
                        .andExpect(jsonPath("$.headerName").value("X-XSRF-TOKEN"))
                        .andReturn();
        var cookie = result.getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        Map<?, ?> body = mapper.readValue(result.getResponse().getContentAsByteArray(), Map.class);
        assertThat(body.get("token")).isEqualTo(cookie.getValue());
        assertThat(cookie.getSecure()).isFalse();
        assertThat(cookie.isHttpOnly()).isFalse();
        assertThat(cookie.getDomain()).isNull();
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax");
        assertThat(result.getRequest().getSession(false)).isNull();
        assertThat(result.getResponse().getCookie("JSESSIONID")).isNull();
    }

    @Test
    void existingCookieKeepsTheSameTokenForConcurrentTabs() throws Exception {
        CsrfBootstrap csrf = bootstrap();
        mvc.perform(get("/api/v1/auth/csrf").cookie(csrf.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value(csrf.token()));
    }

    @Test
    void healthReadIsPublic() throws Exception {
        mvc.perform(get("/api/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void protectedReadReturnsUnauthorizedProblemWithTraceId() throws Exception {
        mvc.perform(
                        get("/api/test/security/private")
                                .header(RequestIdFilter.HEADER, "security-chain-123"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.traceId").value("security-chain-123"))
                .andExpect(header().string(RequestIdFilter.HEADER, "security-chain-123"));
    }

    @Test
    void learnerCannotUseContentAdminMethod() throws Exception {
        mvc.perform(get("/api/test/security/admin").with(user("learner").roles("LEARNER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void systemAdminDoesNotImplicitlyInheritContentAdmin() throws Exception {
        mvc.perform(
                        get("/api/test/security/admin")
                                .with(user("system-admin").roles("SYSTEM_ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void contentAdminCanUseItsMethod() throws Exception {
        mvc.perform(get("/api/test/security/admin").with(user("teacher").roles("CONTENT_ADMIN")))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void everyMutationRequiresCsrfEvenWhenAuthenticated(String method) throws Exception {
        mvc.perform(
                        request(HttpMethod.valueOf(method), "/api/test/security/write")
                                .with(user("learner").roles("LEARNER"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"value\":\"test\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void matchingCookieAndHeaderPassCsrfForAuthenticatedClient(String method) throws Exception {
        CsrfBootstrap csrf = bootstrap();
        mvc.perform(
                        request(HttpMethod.valueOf(method), "/api/test/security/write")
                                .with(user("learner").roles("LEARNER"))
                                .cookie(csrf.cookie())
                                .header(csrf.headerName(), csrf.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"value\":\"test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true));
    }

    @Test
    void mismatchedHeaderIsForbidden() throws Exception {
        CsrfBootstrap csrf = bootstrap();
        mvc.perform(
                        post("/api/test/security/write")
                                .with(user("learner").roles("LEARNER"))
                                .cookie(csrf.cookie())
                                .header(csrf.headerName(), "invalid-token")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"value\":\"test\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void headerWithoutCookieIsForbidden() throws Exception {
        CsrfBootstrap csrf = bootstrap();
        mvc.perform(
                        post("/api/test/security/write")
                                .with(user("learner").roles("LEARNER"))
                                .header(csrf.headerName(), csrf.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"value\":\"test\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void csrfTokenDoesNotAuthenticateTheClient() throws Exception {
        CsrfBootstrap csrf = bootstrap();
        mvc.perform(
                        post("/api/test/security/write")
                                .cookie(csrf.cookie())
                                .header(csrf.headerName(), csrf.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"value\":\"test\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout"})
    void authEndpointsArePermitAllWithoutAuthenticationWhenCsrfValid(String endpoint)
            throws Exception {
        CsrfBootstrap csrf = bootstrap();
        // Since no controller is registered in this test fixture for /api/v1/auth/*,
        // reaching 404 (Not Found) proves it passed Spring Security authorization (not 401
        // Unauthenticated).
        mvc.perform(
                        post(endpoint)
                                .cookie(csrf.cookie())
                                .header(csrf.headerName(), csrf.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout"})
    void authEndpointsStillRequireCsrfProtection(String endpoint) throws Exception {
        mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void allowedOriginPreflightRunsBeforeAuthentication() throws Exception {
        mvc.perform(
                        options("/api/test/security/write")
                                .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                                .header(
                                        HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,
                                        "Content-Type,X-XSRF-TOKEN"))
                .andExpect(status().isOk())
                .andExpect(
                        header().string(
                                        HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                                        "http://localhost:3000"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    @Test
    void untrustedOriginPreflightIsRejected() throws Exception {
        mvc.perform(
                        options("/api/test/security/write")
                                .header(HttpHeaders.ORIGIN, "https://untrusted.example.invalid")
                                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void untrustedOriginCannotReadCsrfBootstrap() throws Exception {
        mvc.perform(
                        get("/api/v1/auth/csrf")
                                .header(HttpHeaders.ORIGIN, "https://untrusted.example.invalid"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    private CsrfBootstrap bootstrap() throws Exception {
        var response =
                mvc.perform(get("/api/v1/auth/csrf"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse();
        Cookie cookie = response.getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        Map<?, ?> payload = mapper.readValue(response.getContentAsByteArray(), Map.class);
        return new CsrfBootstrap(
                cookie, (String) payload.get("headerName"), (String) payload.get("token"));
    }

    private record CsrfBootstrap(Cookie cookie, String headerName, String token) {}

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({
        SecurityConfiguration.class,
        SecurityProblemHandler.class,
        CsrfController.class,
        GlobalExceptionHandler.class,
        JacksonAutoConfiguration.class,
        ProbeController.class
    })
    static class TestApplication {
        @Bean
        UserDetailsService fixtureUserDetailsService() {
            return new InMemoryUserDetailsManager();
        }
    }

    @Profile("security-probe-fixture")
    @RestController
    public static class ProbeController {
        @GetMapping("/api/actuator/health")
        public Map<String, String> health() {
            return Map.of("status", "UP");
        }

        @GetMapping("/api/test/security/private")
        public Map<String, Boolean> read() {
            return Map.of("accepted", true);
        }

        @GetMapping("/api/test/security/admin")
        @PreAuthorize("hasRole('CONTENT_ADMIN')")
        public Map<String, Boolean> admin() {
            return Map.of("accepted", true);
        }

        @RequestMapping(
                path = "/api/test/security/write",
                method = {
                    RequestMethod.POST,
                    RequestMethod.PUT,
                    RequestMethod.PATCH,
                    RequestMethod.DELETE
                })
        public Map<String, Boolean> write(@RequestBody Command command) {
            return Map.of("accepted", true);
        }
    }

    public record Command(String value) {}
}
