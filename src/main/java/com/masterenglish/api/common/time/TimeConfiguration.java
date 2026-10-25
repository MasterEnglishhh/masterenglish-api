package com.masterenglish.api.common.time;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class TimeConfiguration {
    /** Inject this into time-dependent services; use a fixed Clock in their tests. */
    @Bean
    public Clock applicationClock() {
        return Clock.systemUTC();
    }
}
