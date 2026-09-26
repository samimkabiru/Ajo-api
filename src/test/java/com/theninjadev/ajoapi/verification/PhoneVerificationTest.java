package com.theninjadev.ajoapi.verification;

import com.theninjadev.ajoapi.auth.UserRepository;
import com.theninjadev.ajoapi.group.GroupInviteSummary;
import com.theninjadev.ajoapi.group.InviteMemberRequest;
import com.theninjadev.ajoapi.testsupport.AbstractIntegrationTest;
import com.theninjadev.ajoapi.testsupport.AdjustableClock;
import com.theninjadev.ajoapi.testsupport.AdjustableClockConfig;
import com.theninjadev.ajoapi.testsupport.ApiTestClient;
import com.theninjadev.ajoapi.testsupport.TestUser;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The real phone-verification flow, end to end. Fixtures here are registered UNVERIFIED
 * (two-argument ApiTestClient); every other test class verifies its fixtures directly.
 * The code that was "sent" is read from LoggingSmsSender — the database only holds its hash.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AdjustableClockConfig.class)
class PhoneVerificationTest extends AbstractIntegrationTest {

    private static final String REQUEST = "/me/phone/verification/request";
    private static final String CONFIRM = "/me/phone/verification/confirm";
    private static final Pattern SIX_DIGITS = Pattern.compile("\\b(\\d{6})\\b");
    private static final Duration PAST_COOLDOWN = Duration.ofSeconds(61);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private VerificationCodeRepository verificationCodeRepository;
    @Autowired private LoggingSmsSender smsSender;
    @Autowired private Clock clock;

    private ApiTestClient unverified;   // registers users without verifying them
    private ApiTestClient verified;     // registers users already verified

    @BeforeEach
    void setUp() {
        unverified = new ApiTestClient(mockMvc, objectMapper);
        verified = new ApiTestClient(mockMvc, objectMapper, userRepository);
        smsSender.clear();
    }

    // ------------------------------------------------------------------
    // The flow
    // ------------------------------------------------------------------

    @Test
    void requestingACodeSendsOneSixDigitSmsAndReturns202() throws Exception {
        var user = unverified.registerUser("Ada");

        request(user).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.expiresAt").isString())
                .andExpect(jsonPath("$.resendAvailableAt").isString());

        assertThat(smsSender.lastMessageTo(user.phone())).get().asString()
                .containsPattern("Your Ajo verification code is \\d{6}\\. It expires in 10 minutes\\.");
        assertThat(codesFor(user)).hasSize(1);
    }

    @Test
    void theCorrectCodeVerifiesThePhone() throws Exception {
        var user = unverified.registerUser("Ada");
        request(user);

        confirm(user, sentCode(user)).andExpect(status().isOk())
                .andExpect(jsonPath("$.phoneVerified").value(true));

        mockMvc.perform(get("/me").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phoneVerified").value(true));
    }

    @Test
    void aUsedCodeIsConsumedAndCannotBeUsedAgain() throws Exception {
        var user = unverified.registerUser("Ada");
        request(user);
        String code = sentCode(user);
        confirm(user, code).andExpect(status().isOk());

        assertThat(latestCode(user).getConsumedAt()).isNotNull();
        confirm(user, code).andExpect(status().isConflict());     // verified now: nothing left to confirm
    }

    // ------------------------------------------------------------------
    // Attempts — the persisted counter is what proves noRollbackFor works
    // ------------------------------------------------------------------

    @Test
    void aWrongCodeIs400AndTheIncrementIsPersisted() throws Exception {
        var user = unverified.registerUser("Ada");
        request(user);

        confirm(user, wrong(sentCode(user))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("That code is not correct"));

        // Re-read from the database: a rolled-back increment would still read 0 here.
        assertThat(latestCode(user).getAttempts()).isEqualTo(1);
        assertThat(latestCode(user).getConsumedAt()).isNull();
    }

    @Test
    void wrongAttemptsCountUpAndTheOneThatReachesTheCapIs429() throws Exception {
        var user = unverified.registerUser("Ada");
        request(user);
        String wrong = wrong(sentCode(user));

        for (int attempt = 1; attempt <= 4; attempt++) {
            confirm(user, wrong).andExpect(status().isBadRequest());
            assertThat(latestCode(user).getAttempts()).as("attempts after try %d", attempt).isEqualTo(attempt);
        }

        confirm(user, wrong).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.detail").value("Too many incorrect attempts; request a new code"));

        var code = latestCode(user);
        assertThat(code.getAttempts()).isEqualTo(5);
        assertThat(code.getConsumedAt()).as("dead, not merely exhausted").isNotNull();
    }

    @Test
    void afterTheCapEvenTheCorrectCodeIsRejected() throws Exception {
        var user = unverified.registerUser("Ada");
        request(user);
        String code = sentCode(user);
        exhaust(user, code);

        confirm(user, code).andExpect(status().isNotFound());
        assertThat(userRepository.findById(user.id()).orElseThrow().isPhoneVerified()).isFalse();
    }

    @Test
    void afterExhaustionAFreshCodeCanBeRequestedAndWorks() throws Exception {
        var user = unverified.registerUser("Ada");
        request(user);
        exhaust(user, sentCode(user));

        advance(PAST_COOLDOWN);
        request(user).andExpect(status().isAccepted());
        confirm(user, sentCode(user)).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------
    // Expiry
    // ------------------------------------------------------------------

    @Test
    void aCodePastItsTtlIs410() throws Exception {
        var user = unverified.registerUser("Ada");
        request(user);
        String code = sentCode(user);

        advance(Duration.ofMinutes(11));

        confirm(user, code).andExpect(status().isGone())
                .andExpect(jsonPath("$.detail").value("That code has expired; request a new one"));
    }

    @Test
    void anExpiredCodeIsConsumedSoRetryingSaysThereIsNoActiveCode() throws Exception {
        var user = unverified.registerUser("Ada");
        request(user);
        String code = sentCode(user);
        advance(Duration.ofMinutes(11));
        confirm(user, code).andExpect(status().isGone());

        assertThat(latestCode(user).getConsumedAt()).isNotNull();
        confirm(user, code).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // Rate limiting
    // ------------------------------------------------------------------

    @Test
    void aSecondRequestInsideTheCooldownIs429() throws Exception {
        var user = unverified.registerUser("Ada");
        request(user).andExpect(status().isAccepted());

        request(user).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.detail").value("A code was just sent; wait a moment before requesting another"));
    }

    @Test
    void aFourthRequestInsideTheHourIs429() throws Exception {
        var user = unverified.registerUser("Ada");
        for (int i = 0; i < 3; i++) {
            request(user).andExpect(status().isAccepted());
            advance(PAST_COOLDOWN);
        }

        request(user).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.detail").value("Too many verification codes requested; try again later"));
    }

    @Test
    void aNewCodeInvalidatesThePreviousOne() throws Exception {
        var user = unverified.registerUser("Ada");
        request(user);
        String first = sentCode(user);
        advance(PAST_COOLDOWN);
        request(user);
        String second = sentCode(user);
        assumeFalse(first.equals(second), "two random codes collided (1 in 900,000)");

        confirm(user, first).andExpect(status().isBadRequest());
        confirm(user, second).andExpect(status().isOk());
        assertThat(codesFor(user)).allSatisfy(c -> assertThat(c.getConsumedAt()).isNotNull());
    }

    // ------------------------------------------------------------------
    // State and input
    // ------------------------------------------------------------------

    @Test
    void anAlreadyVerifiedUserRequestingACodeIs409() throws Exception {
        var user = verified.registerUser("Ada");

        request(user).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This phone number is already verified"));
    }

    @Test
    void anAlreadyVerifiedUserConfirmingIs409() throws Exception {
        var user = verified.registerUser("Ada");

        confirm(user, "123456").andExpect(status().isConflict());
    }

    @Test
    void bothEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(post(REQUEST)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(CONFIRM).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"123456\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aMalformedCodeIsRejectedAtValidation() throws Exception {
        var user = unverified.registerUser("Ada");
        request(user);

        for (String malformed : List.of("abc", "12345", "", "1234567")) {
            confirm(user, malformed).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("code"));
        }
        assertThat(latestCode(user).getAttempts()).as("validation failures are not attempts").isZero();
    }

    // ------------------------------------------------------------------
    // Secrecy
    // ------------------------------------------------------------------

    @Test
    void theCodeNeverAppearsInAResponseOrInTheDatabase() throws Exception {
        var user = unverified.registerUser("Ada");
        String requestBody = request(user).andReturn().getResponse().getContentAsString();
        String code = sentCode(user);

        String wrongBody = confirm(user, wrong(code)).andReturn().getResponse().getContentAsString();
        String rightBody = confirm(user, code).andReturn().getResponse().getContentAsString();

        assertThat(requestBody).doesNotContain(code);
        assertThat(wrongBody).doesNotContain(code);
        assertThat(rightBody).doesNotContain(code);

        String stored = latestCode(user).getCodeHash();
        assertThat(stored).startsWith("$2").doesNotContain(code);
    }

    // ------------------------------------------------------------------
    // Concurrency — bounded so a timing problem fails fast instead of hanging CI
    // ------------------------------------------------------------------

    @Test
    @Timeout(30)
    void parallelWrongGuessesAreSerialisedSoTheCapHolds() throws Exception {
        var user = unverified.registerUser("Ada");
        request(user);
        String wrong = wrong(sentCode(user));

        List<Integer> statuses = runConcurrently(5, () ->
                confirm(user, wrong).andReturn().getResponse().getStatus());

        assertThat(statuses).containsExactlyInAnyOrder(400, 400, 400, 400, 429);
        var code = latestCode(user);
        assertThat(code.getAttempts()).as("no lost updates").isEqualTo(5);
        assertThat(code.getConsumedAt()).isNotNull();
    }

    @Test
    @Timeout(30)
    void simultaneousRequestsSendOneCodeAndNever500() throws Exception {
        var user = unverified.registerUser("Ada");

        List<Integer> statuses = runConcurrently(2, () ->
                request(user).andReturn().getResponse().getStatus());

        assertThat(statuses).containsExactlyInAnyOrder(202, 429);
        assertThat(codesFor(user)).hasSize(1);
    }

    // ------------------------------------------------------------------
    // The gate: creating or joining a group needs a verified phone
    // ------------------------------------------------------------------

    @Test
    void anUnverifiedUserCannotCreateAGroup() throws Exception {
        var user = unverified.registerUser("Ada");

        createGroup(user).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Verify your phone number before creating or joining a group"));
    }

    @Test
    void anUnverifiedUserCannotAcceptAnInvite() throws Exception {
        var admin = verified.registerUser("Admin");
        var groupId = verified.createGroup(admin, "Admin's Ajo");
        var invitee = unverified.registerUser("Invitee");
        UUID inviteId = invite(admin, groupId, invitee);

        accept(invitee, inviteId).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Verify your phone number before creating or joining a group"));
    }

    @Test
    void afterVerifyingTheSameUserCanCreateAndJoin() throws Exception {
        var admin = verified.registerUser("Admin");
        var groupId = verified.createGroup(admin, "Admin's Ajo");
        var user = unverified.registerUser("Ada");
        UUID inviteId = invite(admin, groupId, user);

        accept(user, inviteId).andExpect(status().isForbidden());
        createGroup(user).andExpect(status().isForbidden());

        request(user);
        confirm(user, sentCode(user)).andExpect(status().isOk());

        accept(user, inviteId).andExpect(status().isOk());
        createGroup(user).andExpect(status().isCreated());
    }

    @Test
    void aVerifiedUsersBehaviourIsUnchanged() throws Exception {
        var admin = verified.registerUser("Admin");
        var groupId = verified.createGroup(admin, "Admin's Ajo");
        var member = verified.registerUser("Member");

        accept(member, invite(admin, groupId, member)).andExpect(status().isOk());
        createGroup(member).andExpect(status().isCreated());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private ResultActions request(TestUser user) throws Exception {
        return mockMvc.perform(post(REQUEST).header("Authorization", bearer(user)));
    }

    private ResultActions confirm(TestUser user, String code) throws Exception {
        return mockMvc.perform(post(CONFIRM)
                .header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new VerifyCodeRequest(code))));
    }

    /** Five wrong attempts: the last one kills the code. */
    private void exhaust(TestUser user, String realCode) throws Exception {
        String wrong = wrong(realCode);
        for (int i = 0; i < 4; i++)
            confirm(user, wrong).andExpect(status().isBadRequest());
        confirm(user, wrong).andExpect(status().isTooManyRequests());
    }

    private String sentCode(TestUser user) {
        String message = smsSender.lastMessageTo(user.phone()).orElseThrow();
        Matcher matcher = SIX_DIGITS.matcher(message);
        assertThat(matcher.find()).as("a six-digit code in: %s", message).isTrue();
        return matcher.group(1);
    }

    /** A different, still well-formed six-digit code. */
    private static String wrong(String code) {
        int value = Integer.parseInt(code);
        return Integer.toString(value == 999999 ? 100000 : value + 1);
    }

    private List<VerificationCode> codesFor(TestUser user) {
        return verificationCodeRepository.findByUserIdAndPurposeAndCreatedAtAfterOrderByCreatedAtDesc(
                user.id(), VerificationPurpose.PHONE_VERIFICATION, Instant.EPOCH);
    }

    private VerificationCode latestCode(TestUser user) {
        return codesFor(user).getFirst();
    }

    private void advance(Duration duration) {
        ((AdjustableClock) clock).advanceBy(duration);
    }

    private ResultActions createGroup(TestUser user) throws Exception {
        return mockMvc.perform(post("/groups")
                .header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"My Ajo\",\"description\":\"description\"}"));
    }

    private UUID invite(TestUser admin, UUID groupId, TestUser invitee) throws Exception {
        var result = mockMvc.perform(post("/groups/" + groupId + "/invites")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new InviteMemberRequest(invitee.phone()))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), GroupInviteSummary.class).id();
    }

    private ResultActions accept(TestUser invitee, UUID inviteId) throws Exception {
        return mockMvc.perform(post("/groups/invites/" + inviteId + "/accept").header("Authorization", bearer(invitee)));
    }

    private static String bearer(TestUser user) {
        return "Bearer " + user.accessToken();
    }

    /** Starts n copies of the task together and returns their results, every wait bounded. */
    private static List<Integer> runConcurrently(int n, Callable<Integer> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).as("all threads ready within 10s").isTrue();
            go.countDown();

            List<Integer> results = new ArrayList<>();
            for (Future<Integer> f : futures)
                results.add(f.get(10, TimeUnit.SECONDS));
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
