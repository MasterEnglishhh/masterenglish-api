package com.masterenglish.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class DashboardPropertiesTests {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(BindingConfig.class);

    @Test
    void grammarDeepLinksAreDisabledByDefault() {
        runner.run(
                context -> {
                    DashboardProperties properties = context.getBean(DashboardProperties.class);
                    assertThat(properties.recommendations().grammarDeepLinksEnabled()).isFalse();
                });
    }

    @Test
    void grammarDeepLinksCanBeEnabledViaProperty() {
        runner.withPropertyValues("dashboard.recommendations.grammar-deep-links-enabled=true")
                .run(
                        context -> {
                            DashboardProperties properties =
                                    context.getBean(DashboardProperties.class);
                            assertThat(properties.recommendations().grammarDeepLinksEnabled())
                                    .isTrue();
                        });
    }

    @Configuration
    @EnableConfigurationProperties(DashboardProperties.class)
    static class BindingConfig {}
}
