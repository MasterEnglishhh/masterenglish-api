package com.masterenglish.api.common.time;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class TimeConfiguration {
    /** Inject this into time-dependent services; use a fixed Clock in their tests. */
    @Bean
    public Clock applicationClock() {
        return Clock.systemUTC();
    }
}
