package com.theninjadev.ajoapi.auth;

import com.theninjadev.ajoapi.testsupport.AbstractIntegrationTest;
import com.theninjadev.ajoapi.testsupport.AdjustableClock;
import com.theninjadev.ajoapi.testsupport.AdjustableClockConfig;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(AdjustableClockConfig.class)
class JwtAuthenticationFilterTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private AdjustableClock clock;

    @TestConfiguration
    static class WhoAmIConfig {
        @Bean
        WhoAmIController whoAmIController() {
            return new WhoAmIController();
        }
    }

    @RestController
    static class WhoAmIController {
        @GetMapping("/test/whoami")
        public String whoAmI() {
            return SecurityContextHolder.getContext().getAuthentication().getPrincipal().toString();
        }
    }

    @Test
    void validAccessTokenAuthenticatesAsTheUserUuid() throws Exception {
        UUID userId = UUID.randomUUID();
        String accessToken = jwtService.generateAccessToken(userId);

        mockMvc.perform(get("/test/whoami").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(content().string(userId.toString()));
    }

    @Test
    void refreshTokenIsRejectedOnProtectedRoute() throws Exception {
        UUID userId = UUID.randomUUID();
        String refreshToken = jwtService.generateRefreshToken(userId);

        mockMvc.perform(get("/test/whoami").header("Authorization", "Bearer " + refreshToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void missingAuthorizationHeaderIsRejected() throws Exception {
        mockMvc.perform(get("/test/whoami"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void garbledTokenIsRejected() throws Exception {
        mockMvc.perform(get("/test/whoami").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        UUID userId = UUID.randomUUID();
        String accessToken = jwtService.generateAccessToken(userId);

        clock.advanceBy(Duration.ofMinutes(16));

        mockMvc.perform(get("/test/whoami").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());
    }
}
