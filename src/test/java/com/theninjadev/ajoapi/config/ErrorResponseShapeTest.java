package com.theninjadev.ajoapi.config;

import com.theninjadev.ajoapi.auth.UserRepository;
import com.theninjadev.ajoapi.testsupport.AbstractIntegrationTest;
import com.theninjadev.ajoapi.testsupport.ApiTestClient;
import com.theninjadev.ajoapi.testsupport.TestUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Every kind of error leaves the API with the same ProblemDetail shape. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ErrorResponseShapeTest.ThrowingController.class)
class ErrorResponseShapeTest extends AbstractIntegrationTest {

    /** Test-only endpoints that fail in ways real endpoints can't be made to on demand. */
    @RestController
    static class ThrowingController {

        @GetMapping("/test-only/boom")
        void boom() {
            throw new RuntimeException("secret internals: db password is hunter2");
        }

        @GetMapping("/test-only/denied")
        void denied() {
            throw new AccessDeniedException("internal access rule text");
        }

        @GetMapping("/test-only/unauthenticated")
        void unauthenticated() {
            throw new BadCredentialsException("internal credential text");
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;

    private ApiTestClient client;

    @BeforeEach
    void setUpClient() {
        client = new ApiTestClient(mockMvc, objectMapper, userRepository);
    }

    @Test
    void malformedJsonIs400() throws Exception {
        expectProblem(mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json")),
                400, "/auth/login");
    }

    @Test
    void validationFailureIs400WithPerFieldErrors() throws Exception {
        expectProblem(mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"\",\"password\":\"short\",\"fullName\":\"\"}")),
                400, "/auth/register")
                .andExpect(jsonPath("$.detail").isString())
                .andExpect(jsonPath("$.errors[*].field", hasItems("phone", "password", "fullName")))
                .andExpect(jsonPath("$.errors[0].message").isString());
    }

    @Test
    void missingTokenIs401() throws Exception {
        expectProblem(mockMvc.perform(get("/groups")), 401, "/groups")
                .andExpect(jsonPath("$.detail").value("Missing or invalid access token"));
    }

    @Test
    void anAuthenticationExceptionFromAHandlerIs401NotA500() throws Exception {
        expectProblem(mockMvc.perform(authed(get("/test-only/unauthenticated"))), 401, "/test-only/unauthenticated")
                .andExpect(jsonPath("$.detail").value("Missing or invalid access token"));
    }

    @Test
    void accessDeniedIs403NotA500() throws Exception {
        String body = expectProblem(mockMvc.perform(authed(get("/test-only/denied"))), 403, "/test-only/denied")
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("internal access rule text");
    }

    @Test
    void unknownRouteIs404() throws Exception {
        expectProblem(mockMvc.perform(authed(get("/no/such/route"))), 404, "/no/such/route");
    }

    @Test
    void unknownRouteDetailNamesOnlyThePath() throws Exception {
        expectProblem(mockMvc.perform(authed(get("/nope"))), 404, "/nope")
                .andExpect(jsonPath("$.detail").value("No endpoint at /nope"));
    }

    @Test
    void aMalformedPathVariableIsStillA400NotAnUnknownRoute() throws Exception {
        expectProblem(mockMvc.perform(authed(get("/groups/not-a-uuid"))), 400, "/groups/not-a-uuid");
    }

    @Test
    void realUnauthenticatedRoutesStillRespond() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        int swagger = mockMvc.perform(get("/swagger-ui.html")).andReturn().getResponse().getStatus();
        assertThat(swagger).as("swagger-ui.html serves or redirects into the UI").isBetween(200, 399);
    }

    @Test
    void wrongMethodIs405() throws Exception {
        expectProblem(mockMvc.perform(get("/auth/login")), 405, "/auth/login");
    }

    @Test
    void unsupportedMediaTypeIs415() throws Exception {
        expectProblem(mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("phone=0801")),
                415, "/auth/login");
    }

    @Test
    void anUnexpectedExceptionIsAGeneric500() throws Exception {
        String body = expectProblem(mockMvc.perform(authed(get("/test-only/boom"))), 500, "/test-only/boom")
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("secret internals").doesNotContain("hunter2")
                .doesNotContain("RuntimeException");
    }

    // ------------------------------------------------------------------

    /** The one shape every error shares. */
    private ResultActions expectProblem(ResultActions result, int status, String path) throws Exception {
        return result
                .andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.title").isString())
                .andExpect(jsonPath("$.detail").isString())
                .andExpect(jsonPath("$.instance").value(path));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authed(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        TestUser user = client.registerUser("Shape Tester");
        return request.header("Authorization", "Bearer " + user.accessToken());
    }
}
