package org.sopt.hashi.auth.internal.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.web.cors.CorsConfiguration;

@ConfigurationProperties(prefix = "hashi.cors")
public record CorsProperties(
        List<String> allowedOrigins,
        List<String> allowedOriginPatterns) {

    public CorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        allowedOriginPatterns = allowedOriginPatterns == null
                ? List.of()
                : List.copyOf(allowedOriginPatterns);
    }

    CorsConfiguration createCorsConfiguration() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedOriginPatterns(allowedOriginPatterns);
        return configuration;
    }
}
