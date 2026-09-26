package com.theninjadev.ajoapi.auth;

import tools.jackson.databind.ObjectMapper;
import com.theninjadev.ajoapi.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtService jwtService;

    @Test
    void registerReturnsCreatedWithoutPasswordHashAndSetsHttpOnlyCookie() throws Exception {
        var request = new RegisterRequest("08011122001", "password123", "Jane Doe", null);

        var result = mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.user.phone").value("+2348011122001"))
                .andExpect(cookie().httpOnly("refresh_token", true))
                .andExpect(cookie().secure("refresh_token", true))
                .andExpect(cookie().path("refresh_token", "/auth"))
                .andReturn();

        var body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("passwordHash");

        var setCookieHeader = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookieHeader).contains("SameSite=Strict");
    }

    @Test
    void registerWithDuplicatePhoneReturnsConflict() throws Exception {
        var request = new RegisterRequest("08011122002", "password123", "Jane Doe", null);
        mockMvc.perform(post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }

    @Test
    void registerWithInvalidPhoneReturnsBadRequest() throws Exception {
        var request = new RegisterRequest("0612345678", "password123", "Jane Doe", null);

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void loginWithCorrectCredentialsSucceeds() throws Exception {
        var registerRequest = new RegisterRequest("08011122003", "password123", "Jane Doe", null);
        mockMvc.perform(post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(registerRequest)));

        var loginRequest = new LoginRequest("08011122003", "password123");
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists());
    }

    @Test
    void loginWithWrongPasswordReturnsUnauthorized() throws Exception {
        var registerRequest = new RegisterRequest("08011122004", "password123", "Jane Doe", null);
        mockMvc.perform(post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(registerRequest)));

        var loginRequest = new LoginRequest("08011122004", "wrongpassword");
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isUnauthorized());
    }

    // ---- GET /me

    @Test
    void meReturnsTheCurrentUserForAValidAccessToken() throws Exception {
        var register = register(new RegisterRequest("08011123001", "password123", "Me Myself", "me@example.com"));
        String accessToken = objectMapper.readTree(register.getResponse().getContentAsString())
                .get("accessToken").asString();

        var body = mockMvc.perform(get("/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value("+2348011123001"))
                .andExpect(jsonPath("$.fullName").value("Me Myself"))
                .andExpect(jsonPath("$.email").value("me@example.com"))
                .andExpect(jsonPath("$.phoneVerified").value(false))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("password");
    }

    @Test
    void meWithoutATokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void meWithARefreshTokenIsUnauthorized() throws Exception {
        var register = register(new RegisterRequest("08011123002", "password123", "Jane Doe", null));
        String refreshToken = register.getResponse().getCookie("refresh_token").getValue();

        mockMvc.perform(get("/me").header("Authorization", "Bearer " + refreshToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void meForAUserWhoNoLongerExistsIsUnauthorizedNotAServerError() throws Exception {
        String tokenForNobody = jwtService.generateAccessToken(UUID.randomUUID());

        mockMvc.perform(get("/me").header("Authorization", "Bearer " + tokenForNobody))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Missing or invalid access token"));
    }

    // ---- Registration input

    @Test
    void emailDifferingOnlyByCaseReturnsConflict() throws Exception {
        register(new RegisterRequest("08011123003", "password123", "Ada", "Ada@X.com"));

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest("08011123004", "password123", "Ada Again", "ada@x.com"))))
                .andExpect(status().isConflict());
    }

    @Test
    void passwordShorterThanEightCharactersIsRejected() throws Exception {
        expectFieldRejected(new RegisterRequest("08011123005", "short7!", "Jane Doe", null), "password");
    }

    @Test
    void multiBytePasswordOver72BytesIsRejectedNotA500() throws Exception {
        String ninetyBytes = "\u20a6".repeat(30);        // 30 characters of the naira sign, 3 bytes each
        expectFieldRejected(new RegisterRequest("08011123006", ninetyBytes, "Jane Doe", null), "password");

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("08011123006", ninetyBytes))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void overLengthEmailIsRejected() throws Exception {
        String email = "a".repeat(60) + "@" + "b".repeat(190) + ".com";   // 255 characters
        expectFieldRejected(new RegisterRequest("08011123007", "password123", "Jane Doe", email), "email");
    }

    @Test
    void overLengthFullNameIsRejected() throws Exception {
        expectFieldRejected(new RegisterRequest("08011123008", "password123", "N".repeat(101), null), "fullName");
    }

    @Test
    void dashedPhoneIsNormalised() throws Exception {
        register(new RegisterRequest("0801-112-3009", "password123", "Jane Doe", null));

        // Registered with dashes, logs in with parentheses and spaces: both normalise to one E.164 number.
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("(0801) 112 3009", "password123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.phone").value("+2348011123009"));
    }

    private MvcResult register(RegisterRequest request) throws Exception {
        return mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
    }

    private void expectFieldRejected(RegisterRequest request, String field) throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem(field)));
    }

    @Test
    void refreshRotatesCookieAndLogoutClearsIt() throws Exception {
        var registerRequest = new RegisterRequest("08011122005", "password123", "Jane Doe", null);
        var registerResult = mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andReturn();
        var refreshCookie = registerResult.getResponse().getCookie("refresh_token");

        var refreshResult = mockMvc.perform(post("/auth/refresh").cookie(refreshCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andReturn();
        var rotatedCookie = refreshResult.getResponse().getCookie("refresh_token");
        assertThat(rotatedCookie.getValue()).isNotEqualTo(refreshCookie.getValue());

        mockMvc.perform(post("/auth/logout").cookie(rotatedCookie))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));
    }
}
