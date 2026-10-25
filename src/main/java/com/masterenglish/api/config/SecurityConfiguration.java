package com.masterenglish.api.config;

import com.masterenglish.api.common.security.SecurityProblemHandler;
import jakarta.servlet.DispatcherType;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(WebSecurityProperties.class)
public class SecurityConfiguration {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SecurityProblemHandler problemHandler,
            CookieCsrfTokenRepository csrfTokenRepository,
            UrlBasedCorsConfigurationSource corsConfigurationSource)
            throws Exception {

        http.formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(
                        sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                // spa() installs Spring's plain-header/XOR handling; override its default
                // repository afterwards.
                .csrf(csrf -> csrf.spa().csrfTokenRepository(csrfTokenRepository))
                .exceptionHandling(
                        errors ->
                                errors.authenticationEntryPoint(problemHandler)
                                        .accessDeniedHandler(problemHandler))
                .authorizeHttpRequests(
                        requests ->
                                requests.dispatcherTypeMatchers(DispatcherType.ERROR)
                                        .permitAll()
                                        .requestMatchers(
                                                HttpMethod.GET,
                                                "/api/actuator/health",
                                                "/api/actuator/health/**")
                                        .permitAll()
                                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf")
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated());

        return http.build();
    }

    @Bean
    public CookieCsrfTokenRepository csrfTokenRepository(WebSecurityProperties properties) {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookiePath("/");
        repository.setCookieCustomizer(
                cookie -> cookie.secure(properties.cookieSecure()).sameSite("Lax"));
        return repository;
    }

    @Bean
    public UrlBasedCorsConfigurationSource corsConfigurationSource(
            WebSecurityProperties properties) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(properties.allowedOrigins());
        cors.setAllowedMethods(List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(
                List.of(
                        "Content-Type",
                        "Authorization",
                        "X-XSRF-TOKEN",
                        "X-Request-Id",
                        "Idempotency-Key",
                        "If-Match"));
        cors.setExposedHeaders(List.of("X-Request-Id", "ETag", "Retry-After"));
        cors.setAllowCredentials(true);
        cors.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }
}
