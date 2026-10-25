package com.masterenglish.api.config;

import java.net.URI;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Browser origins and cookie policy are environment-specific; secrets do not belong here. */
@ConfigurationProperties("app.security")
public record WebSecurityProperties(List<String> allowedOrigins, boolean cookieSecure) {
    public WebSecurityProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        for (String origin : allowedOrigins) {
            URI uri = URI.create(origin);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
                    || uri.getPort() == 0
                    || uri.getPort() > 65535) {
                throw new IllegalArgumentException(
                        "Allowed origins must be explicit http(s) origins without a path, query or credentials.");
            }
        }
    }
}
