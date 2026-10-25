package com.masterenglish.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.masterenglish.api.common.error.GlobalExceptionHandler;
import com.masterenglish.api.common.error.RequestIdFilter;
import com.masterenglish.api.common.web.JsonRequestSizeFilter;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * Starts real Tomcat with the real Modulith post-processors and an isolated PostgreSQL container.
 */
@Import({
    TestcontainersConfiguration.class,
    MasterenglishApiApplicationTests.ProbeConfiguration.class
})
@ActiveProfiles("servlet-probe-fixture")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=127.0.0.1", "app.security.cookie-secure=false"})
class MasterenglishApiApplicationTests {
    @LocalServerPort int port;
    @Autowired ApplicationContext context;
    @Autowired JsonMapper mapper;

    @Test
    void contextLoads() {
        assertThat(port).isPositive();
    }

    @Test
    void servletSupportBeansAreNotObservabilityProxies() {
        assertThat(AopUtils.isAopProxy(context.getBean(RequestIdFilter.class))).isFalse();
        assertThat(AopUtils.isAopProxy(context.getBean(JsonRequestSizeFilter.class))).isFalse();
        assertThat(AopUtils.isAopProxy(context.getBean(GlobalExceptionHandler.class))).isFalse();
    }

    @Test
    void healthIsReachableThroughInitializedServletFilters() throws Exception {
        var response =
                send(
                        request("/api/actuator/health")
                                .header(RequestIdFilter.HEADER, "real-servlet-test")
                                .GET()
                                .build());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).get("status")).isEqualTo("UP");
        assertThat(response.headers().firstValue(RequestIdFilter.HEADER))
                .contains("real-servlet-test");
    }

    @Test
    void csrfBootstrapWritesSameSiteCookieOnRealHttpResponse() throws Exception {
        var response = send(request("/api/v1/auth/csrf").GET().build());
        assertThat(response.statusCode()).isEqualTo(200);
        String cookie = response.headers().firstValue("Set-Cookie").orElseThrow();
        assertThat(cookie)
                .startsWith("XSRF-TOKEN=")
                .contains("Path=/", "SameSite=Lax")
                .doesNotContain("HttpOnly", "Domain=");
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow())
                .contains("no-store");
        assertThat(cookie.split(";", 2)[0]).isEqualTo("XSRF-TOKEN=" + json(response).get("token"));
    }

    @Test
    void declaredOversizeIsRejectedOverHttp() throws Exception {
        var response =
                send(
                        request("/api/test/protected")
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(oversizedBody()))
                                .build());
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(json(response).get("code")).isEqualTo("PAYLOAD_TOO_LARGE");
    }

    @Test
    void unknownLengthStreamCannotBypassByteLimitOverHttp() throws Exception {
        byte[] body = oversizedBody().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var publisher =
                HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body));
        assertThat(publisher.contentLength()).isEqualTo(-1);
        var response =
                send(
                        request("/api/test/protected")
                                .header("Content-Type", "application/json")
                                .POST(publisher)
                                .build());
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(json(response).get("code")).isEqualTo("PAYLOAD_TOO_LARGE");
    }

    @Test
    void csrfProtectionAndAuthenticationRemainSeparateOverHttp() throws Exception {
        var bootstrap = send(request("/api/v1/auth/csrf").GET().build());
        String cookie = bootstrap.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        Map<?, ?> csrf = json(bootstrap);
        var noToken =
                send(
                        request("/api/test/protected")
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                                .build());
        assertThat(noToken.statusCode()).isEqualTo(403);
        var withToken =
                send(
                        request("/api/test/protected")
                                .header("Content-Type", "application/json")
                                .header("Cookie", cookie)
                                .header((String) csrf.get("headerName"), (String) csrf.get("token"))
                                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                                .build());
        assertThat(withToken.statusCode()).isEqualTo(401);
        assertThat(json(withToken).get("code")).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void mvcErrorsStillReturnProblemDetailsOnRealServer() throws Exception {
        var response = send(request("/api/v1/auth/csrf?probe&limit=not-a-number").GET().build());
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response).get("code")).isEqualTo("VALIDATION_ERROR");
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15));
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        try (HttpClient client =
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(5))
                        .build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private Map<?, ?> json(HttpResponse<String> response) {
        return mapper.readValue(response.body(), Map.class);
    }

    private static String oversizedBody() {
        return "{\"value\":\"" + "a".repeat(1024 * 1024) + "\"}";
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        @Bean
        ValidationProbeController servletValidationProbe() {
            return new ValidationProbeController();
        }
    }

    @Profile("servlet-probe-fixture")
    @RestController
    static class ValidationProbeController {
        @GetMapping(path = "/api/v1/auth/csrf", params = "probe")
        Map<String, Integer> probe(@RequestParam int limit) {
            return Map.of("limit", limit);
        }
    }
}
