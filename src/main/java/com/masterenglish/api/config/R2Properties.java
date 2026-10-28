package com.masterenglish.api.config;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.storage.r2")
public record R2Properties(
        boolean enabled,
        String endpoint,
        String region,
        String bucket,
        String accessKeyId,
        String secretAccessKey) {
    public R2Properties {
        if (enabled) {
            if (blank(endpoint)
                    || blank(region)
                    || blank(bucket)
                    || blank(accessKeyId)
                    || blank(secretAccessKey)) {
                throw new IllegalArgumentException(
                        "Enabled R2 requires endpoint, region, bucket and credentials.");
            }
            URI uri = URI.create(endpoint);
            if (!"https".equals(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || (uri.getRawPath() != null
                            && !uri.getRawPath().isEmpty()
                            && !"/".equals(uri.getRawPath()))) {
                throw new IllegalArgumentException(
                        "R2 endpoint must be an HTTPS origin without credentials or a bucket path.");
            }
            if (!bucket.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]")) {
                throw new IllegalArgumentException(
                        "R2 bucket must contain 3-63 lowercase letters, digits or hyphens.");
            }
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    @Override
    public String toString() {
        return "R2Properties[enabled=" + enabled + ", credentials=REDACTED]";
    }
}
