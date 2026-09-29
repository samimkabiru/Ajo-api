package com.theninjadev.ajoapi.auth;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Most tests run with other limits (the test profile raises the threshold, LoginRateLimitTest
 * lowers it), so pin what the application actually ships with here.
 */
class LoginRateLimitPropertiesTest {

    @Test
    void applicationYmlShipsFiveAttemptsInFifteenMinutesThenAFifteenMinuteBlock() throws Exception {
        var sources = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));

        var properties = new Binder(ConfigurationPropertySources.from(sources))
                .bind("app.security.login-rate-limit", LoginRateLimitProperties.class)
                .get();

        assertThat(properties.maxAttempts()).isEqualTo(5);
        assertThat(properties.window()).isEqualTo(Duration.ofMinutes(15));
        assertThat(properties.blockDuration()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void missingValuesFallBackToTheSameDefaults() {
        var properties = new LoginRateLimitProperties(0, null, null);

        assertThat(properties.maxAttempts()).isEqualTo(5);
        assertThat(properties.window()).isEqualTo(Duration.ofMinutes(15));
        assertThat(properties.blockDuration()).isEqualTo(Duration.ofMinutes(15));
    }
}
