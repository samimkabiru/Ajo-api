package com.theninjadev.ajoapi.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Origins allowed to call the API from a browser, bound from CORS_ALLOWED_ORIGINS (comma-separated). */
@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(List<String> allowedOrigins) {

    public CorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : allowedOrigins.stream()
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
    }
}
