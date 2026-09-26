package com.theninjadev.ajoapi.verification;

import com.theninjadev.ajoapi.auth.LoginRequest;
import com.theninjadev.ajoapi.auth.RefreshTokenRepository;
import com.theninjadev.ajoapi.auth.UserRepository;
import com.theninjadev.ajoapi.testsupport.AbstractIntegrationTest;
import com.theninjadev.ajoapi.testsupport.AdjustableClock;
import com.theninjadev.ajoapi.testsupport.AdjustableClockConfig;
import com.theninjadev.ajoapi.testsupport.ApiTestClient;
import com.theninjadev.ajoapi.testsupport.TestUser;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Password reset, end to end. The flow is unauthenticated, so most of these tests are about
 * what the API refuses to reveal. The code that was "sent" is read from LoggingSmsSender.
 * Timing is covered separately in PasswordResetTimingTest.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AdjustableClockConfig.class)
class PasswordResetTest extends AbstractIntegrationTest {

    private static final String REQUEST = "/auth/password-reset/request";
    private static final String CONFIRM = "/auth/password-reset/confirm";
    private static final String PASSWORD = "password123";          // what ApiTestClient registers with
    private static final String NEW_PASSWORD = "brand-new-pass-9";
    private static final Pattern SIX_DIGITS = Pattern.compile("\\b(\\d{6})\\b");
    private static final Duration PAST_COOLDOWN = Duration.ofSeconds(61);

    // Numbers nobody registers: a prefix no fixture uses.
    private static final AtomicInteger UNREGISTERED = new AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private VerificationCodeRepository verificationCodeRepository;
    @Autowired private LoggingSmsSender smsSender;
    @Autowired private Clock clock;

    private ApiTestClient verified;
    private ApiTestClient unverified;

    @BeforeEach
    void setUp() {
        verified = new ApiTestClient(mockMvc, objectMapper, userRepository);
        unverified = new ApiTestClient(mockMvc, objectMapper);
        smsSender.clear();
    }

    // ------------------------------------------------------------------
    // The happy path
    // ------------------------------------------------------------------

    @Test
    void aRequestForARegisteredPhoneSendsOneSixDigitCode() throws Exception {
        var user = verified.registerUser("Ada");

        requestReset(user.phone()).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.ttlSeconds").value(600))
                .andExpect(jsonPath("$.resendCooldownSeconds").value(60));

        assertThat(smsSender.lastMessageTo(user.phone())).get().asString()
                .containsPattern("Your Ajo password reset code is \\d{6}\\. It expires in 10 minutes\\.");
        assertThat(resetCodes(user)).hasSize(1);
    }

    @Test
    void confirmingChangesThePassword() throws Exception {
        var user = verified.registerUser("Ada");
        requestReset(user.phone());

        confirmReset(user.phone(), sentCode(user), NEW_PASSWORD).andExpect(status().isNoContent());

        login(user, PASSWORD).andExpect(status().isUnauthorized());
        login(user, NEW_PASSWORD).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------
    // Non-disclosure — the point of the slice
    // ------------------------------------------------------------------

    @Test
    void anUnregisteredPhoneGets202AndNothingIsSent() throws Exception {
        String nobody = unregisteredPhone();

        requestReset(nobody).andExpect(status().isAccepted());

        assertThat(smsSender.lastMessageTo(toE164(nobody))).isEmpty();
    }

    @Test
    void knownAndUnknownPhonesGetIdenticalResponses() throws Exception {
        var user = verified.registerUser("Ada");

        var known = requestReset(user.phone()).andReturn().getResponse();
        var unknown = requestReset(unregisteredPhone()).andReturn().getResponse();

        assertThat(unknown.getStatus()).isEqualTo(known.getStatus()).isEqualTo(202);
        assertThat(unknown.getContentAsString()).isEqualTo(known.getContentAsString());
    }

    @Test
    void aRateLimitedRequestStillReturns202AndSendsNothing() throws Exception {
        var user = verified.registerUser("Ada");
        String first = requestReset(user.phone()).andReturn().getResponse().getContentAsString();
        smsSender.clear();

        // Inside the cooldown.
        var cooledDown = requestReset(user.phone()).andReturn().getResponse();
        assertThat(cooledDown.getStatus()).isEqualTo(202);
        assertThat(cooledDown.getContentAsString()).isEqualTo(first);
        assertThat(smsSender.lastMessageTo(user.phone())).isEmpty();

        // Past the hourly window's limit.
        advance(PAST_COOLDOWN);
        requestReset(user.phone());
        advance(PAST_COOLDOWN);
        requestReset(user.phone());
        advance(PAST_COOLDOWN);
        smsSender.clear();
        var overLimit = requestReset(user.phone()).andReturn().getResponse();
        assertThat(overLimit.getStatus()).isEqualTo(202);
        assertThat(overLimit.getContentAsString()).isEqualTo(first);
        assertThat(smsSender.lastMessageTo(user.phone())).isEmpty();
        assertThat(resetCodes(user)).hasSize(3);
    }

    @Test
    void confirmFailsIdenticallyForEveryFailureMode() throws Exception {
        List<String> bodies = new ArrayList<>();

        // Unknown phone.
        bodies.add(failedConfirm(unregisteredPhone(), "123456"));

        // Registered, but no code ever requested.
        var noCode = verified.registerUser("No Code");
        bodies.add(failedConfirm(noCode.phone(), "123456"));

        // Wrong code.
        var wrong = verified.registerUser("Wrong");
        requestReset(wrong.phone());
        bodies.add(failedConfirm(wrong.phone(), wrongCode(sentCode(wrong))));

        // Expired code.
        var expired = verified.registerUser("Expired");
        requestReset(expired.phone());
        String expiredCode = sentCode(expired);
        advance(Duration.ofMinutes(11));
        bodies.add(failedConfirm(expired.phone(), expiredCode));

        // Exhausted attempts — then even the correct code.
        var exhausted = verified.registerUser("Exhausted");
        requestReset(exhausted.phone());
        String correct = sentCode(exhausted);
        exhaust(exhausted, correct);
        bodies.add(failedConfirm(exhausted.phone(), correct));

        assertThat(bodies).hasSize(5).allSatisfy(body -> assertThat(body).isEqualTo(bodies.getFirst()));
        assertThat(bodies.getFirst()).contains("That reset code is not valid");
    }

    // ------------------------------------------------------------------
    // Purpose isolation
    // ------------------------------------------------------------------

    @Test
    void aPhoneVerificationCodeCannotResetAPassword() throws Exception {
        var user = unverified.registerUser("Ada");
        mockMvc.perform(post("/me/phone/verification/request").header("Authorization", bearer(user)))
                .andExpect(status().isAccepted());
        String verificationCode = sentCode(user);

        confirmReset(user.phone(), verificationCode, NEW_PASSWORD).andExpect(status().isBadRequest());

        login(user, PASSWORD).andExpect(status().isOk());      // password unchanged
    }

    @Test
    void aPasswordResetCodeCannotVerifyAPhone() throws Exception {
        var user = unverified.registerUser("Ada");
        requestReset(user.phone());
        String resetCode = sentCode(user);

        confirmPhone(user, resetCode).andExpect(status().isNotFound());   // no live PHONE_VERIFICATION code

        assertThat(userRepository.findById(user.id()).orElseThrow().isPhoneVerified()).isFalse();
    }

    @Test
    void aUserMayHoldOneLiveCodeOfEachKindAndEachWorksForItsOwnFlow() throws Exception {
        var user = unverified.registerUser("Ada");
        mockMvc.perform(post("/me/phone/verification/request").header("Authorization", bearer(user)))
                .andExpect(status().isAccepted());
        String verificationCode = sentCode(user);
        requestReset(user.phone());
        String resetCode = sentCode(user);

        confirmPhone(user, verificationCode).andExpect(status().isOk());
        confirmReset(user.phone(), resetCode, NEW_PASSWORD).andExpect(status().isNoContent());
        login(user, NEW_PASSWORD).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------
    // Sessions
    // ------------------------------------------------------------------

    @Test
    void aRefreshTokenIssuedBeforeTheResetFailsAfterIt() throws Exception {
        var user = verified.registerUser("Ada");
        Cookie before = refreshCookieFrom(login(user, PASSWORD));

        resetPassword(user);

        mockMvc.perform(post("/auth/refresh").cookie(before)).andExpect(status().isUnauthorized());
    }

    @Test
    void everyRefreshTokenForTheUserIsRevoked() throws Exception {
        var user = verified.registerUser("Ada");
        List<Cookie> sessions = List.of(
                refreshCookieFrom(login(user, PASSWORD)),
                refreshCookieFrom(login(user, PASSWORD)),
                refreshCookieFrom(login(user, PASSWORD)));
        assertThat(refreshTokenRepository.findByUserIdAndRevokedAtIsNull(user.id())).hasSizeGreaterThanOrEqualTo(3);

        resetPassword(user);

        assertThat(refreshTokenRepository.findByUserIdAndRevokedAtIsNull(user.id())).isEmpty();
        for (Cookie session : sessions)
            mockMvc.perform(post("/auth/refresh").cookie(session)).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // Attempts — the persisted counter is what proves noRollbackFor works
    // ------------------------------------------------------------------

    @Test
    void aWrongCodeIncrementsThePersistedCounter() throws Exception {
        var user = verified.registerUser("Ada");
        requestReset(user.phone());

        confirmReset(user.phone(), wrongCode(sentCode(user)), NEW_PASSWORD).andExpect(status().isBadRequest());

        // Re-read from the database: a rolled-back increment would still read 0 here.
        assertThat(latestResetCode(user).getAttempts()).isEqualTo(1);
    }

    @Test
    void theCounterClimbsToTheCapThenTheCodeIsDeadEvenForTheCorrectCode() throws Exception {
        var user = verified.registerUser("Ada");
        requestReset(user.phone());
        String correct = sentCode(user);
        String wrong = wrongCode(correct);

        for (int attempt = 1; attempt <= 5; attempt++) {
            confirmReset(user.phone(), wrong, NEW_PASSWORD).andExpect(status().isBadRequest());
            assertThat(latestResetCode(user).getAttempts()).as("attempts after try %d", attempt).isEqualTo(attempt);
        }
        assertThat(latestResetCode(user).getConsumedAt()).isNotNull();

        confirmReset(user.phone(), correct, NEW_PASSWORD).andExpect(status().isBadRequest());
        login(user, NEW_PASSWORD).andExpect(status().isUnauthorized());
        login(user, PASSWORD).andExpect(status().isOk());
    }

    @Test
    @Timeout(30)
    void parallelWrongConfirmsRecordExactlyTheCap() throws Exception {
        var user = verified.registerUser("Ada");
        requestReset(user.phone());
        String wrong = wrongCode(sentCode(user));

        List<Integer> statuses = runConcurrently(5, () ->
                confirmReset(user.phone(), wrong, NEW_PASSWORD).andReturn().getResponse().getStatus());

        assertThat(statuses).containsOnly(400);
        var code = latestResetCode(user);
        assertThat(code.getAttempts()).as("no lost updates").isEqualTo(5);
        assertThat(code.getConsumedAt()).isNotNull();
    }

    // ------------------------------------------------------------------
    // Boundaries
    // ------------------------------------------------------------------

    @Test
    void aNewPasswordShorterThanEightCharactersIsRejected() throws Exception {
        var user = verified.registerUser("Ada");
        requestReset(user.phone());

        confirmReset(user.phone(), sentCode(user), "short7!").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("newPassword"));
        login(user, PASSWORD).andExpect(status().isOk());
    }

    @Test
    void aNewPasswordOver72BytesIsRejected() throws Exception {
        var user = verified.registerUser("Ada");
        requestReset(user.phone());

        String ninetyBytes = "₦".repeat(30);        // 30 characters of the naira sign, 3 bytes each
        confirmReset(user.phone(), sentCode(user), ninetyBytes).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("newPassword"));
    }

    @Test
    void aResetLeavesPhoneVerifiedExactlyAsItWas() throws Exception {
        var alreadyVerified = verified.registerUser("Verified");
        resetPassword(alreadyVerified);
        assertThat(userRepository.findById(alreadyVerified.id()).orElseThrow().isPhoneVerified()).isTrue();

        var notVerified = unverified.registerUser("Unverified");
        resetPassword(notVerified);
        assertThat(userRepository.findById(notVerified.id()).orElseThrow().isPhoneVerified()).isFalse();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private ResultActions requestReset(String phone) throws Exception {
        return mockMvc.perform(post(REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new PasswordResetRequest(phone))));
    }

    private ResultActions confirmReset(String phone, String code, String newPassword) throws Exception {
        return mockMvc.perform(post(CONFIRM)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new ConfirmPasswordResetRequest(phone, code, newPassword))));
    }

    private String failedConfirm(String phone, String code) throws Exception {
        return confirmReset(phone, code, NEW_PASSWORD)
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
    }

    private void resetPassword(TestUser user) throws Exception {
        requestReset(user.phone());
        confirmReset(user.phone(), sentCode(user), NEW_PASSWORD).andExpect(status().isNoContent());
    }

    private void exhaust(TestUser user, String correct) throws Exception {
        String wrong = wrongCode(correct);
        for (int i = 0; i < 5; i++)
            confirmReset(user.phone(), wrong, NEW_PASSWORD).andExpect(status().isBadRequest());
    }

    private ResultActions confirmPhone(TestUser user, String code) throws Exception {
        return mockMvc.perform(post("/me/phone/verification/confirm")
                .header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new VerifyCodeRequest(code))));
    }

    private ResultActions login(TestUser user, String password) throws Exception {
        return mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest(user.phone(), password))));
    }

    private static Cookie refreshCookieFrom(ResultActions login) throws Exception {
        Cookie cookie = login.andExpect(status().isOk()).andReturn().getResponse().getCookie("refresh_token");
        assertThat(cookie).isNotNull();
        return cookie;
    }

    private String sentCode(TestUser user) {
        String message = smsSender.lastMessageTo(user.phone()).orElseThrow();
        Matcher matcher = SIX_DIGITS.matcher(message);
        assertThat(matcher.find()).as("a six-digit code in: %s", message).isTrue();
        return matcher.group(1);
    }

    private static String wrongCode(String code) {
        int value = Integer.parseInt(code);
        return Integer.toString(value == 999999 ? 100000 : value + 1);
    }

    private List<VerificationCode> resetCodes(TestUser user) {
        return verificationCodeRepository.findByUserIdAndPurposeAndCreatedAtAfterOrderByCreatedAtDesc(
                user.id(), VerificationPurpose.PASSWORD_RESET, Instant.EPOCH);
    }

    private VerificationCode latestResetCode(TestUser user) {
        return resetCodes(user).getFirst();
    }

    private void advance(Duration duration) {
        ((AdjustableClock) clock).advanceBy(duration);
    }

    private static String unregisteredPhone() {
        return "0806%07d".formatted(UNREGISTERED.incrementAndGet());
    }

    private static String toE164(String local) {
        return "+234" + local.substring(1);
    }

    private static String bearer(TestUser user) {
        return "Bearer " + user.accessToken();
    }

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
