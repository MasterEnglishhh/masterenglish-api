package com.masterenglish.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class LocalEnvironmentConfigurationTests {
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void defaultPolicyRequiresSecureCookieAndDoesNotOpenCrossOriginAccess() {
        runner.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    var properties = context.getBean(WebSecurityProperties.class);
                    assertThat(properties.cookieSecure()).isTrue();
                    assertThat(properties.allowedOrigins()).isEmpty();
                });
    }

    @Test
    void localProfileLoadsTheSelectedBackendEnvWithoutInfra(@TempDir Path directory)
            throws Exception {
        Path env = directory.resolve("backend.env");
        Files.writeString(
                env,
                """
                POSTGRES_HOST=127.0.0.1
                POSTGRES_PORT=15439
                POSTGRES_DB=fixture_db
                POSTGRES_USER=fixture_user
                POSTGRES_PASSWORD=fixture-only-not-a-real-secret
                SERVER_PORT=8099
                APP_SECURITY_ALLOWED_ORIGINS=http://localhost:3001
                APP_SECURITY_COOKIE_SECURE=false
                """);
        runner.withPropertyValues(
                        "spring.profiles.active=local",
                        "MASTERENGLISH_ENV_FILE=" + env.toString().replace('\\', '/'))
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context.getEnvironment().getProperty("spring.config.import"))
                                    .doesNotContain("masterenglish-infra");
                            assertThat(
                                            context.getEnvironment()
                                                    .getProperty("spring.datasource.url"))
                                    .isEqualTo("jdbc:postgresql://127.0.0.1:15439/fixture_db");
                            assertThat(context.getEnvironment().getProperty("server.port"))
                                    .isEqualTo("8099");
                            var properties = context.getBean(WebSecurityProperties.class);
                            assertThat(properties.allowedOrigins())
                                    .containsExactly("http://localhost:3001");
                            assertThat(properties.cookieSecure()).isFalse();
                        });
    }

    @Test
    void httpsPolicyActuallySetsSecureOnCsrfCookie() {
        var properties = new WebSecurityProperties(List.of("https://app.example.invalid"), true);
        var repository = new SecurityConfiguration().csrfTokenRepository(properties);
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        repository.saveToken(repository.generateToken(request), request, response);
        var cookie = response.getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getSecure()).isTrue();
        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax");
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.getDomain()).isNull();
    }

    @Test
    void originsMustNotContainWildcardPathsOrCredentials() {
        for (String origin :
                List.of(
                        "*",
                        "null",
                        "https://*.example.invalid",
                        "https://app.example.invalid/",
                        "https://app.example.invalid/path",
                        "https://name:password@app.example.invalid",
                        "https://app.example.invalid?q=1")) {
            assertThatThrownBy(() -> new WebSecurityProperties(List.of(origin), false))
                    .as(origin)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(WebSecurityProperties.class)
    static class PropertiesConfiguration {}
}
