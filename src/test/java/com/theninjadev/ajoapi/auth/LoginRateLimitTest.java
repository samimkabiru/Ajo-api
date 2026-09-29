package com.theninjadev.ajoapi.auth;

import com.theninjadev.ajoapi.testsupport.AbstractIntegrationTest;
import com.theninjadev.ajoapi.testsupport.AdjustableClock;
import com.theninjadev.ajoapi.testsupport.AdjustableClockConfig;
import com.theninjadev.ajoapi.testsupport.ApiTestClient;
import com.theninjadev.ajoapi.testsupport.TestUser;
import com.theninjadev.ajoapi.verification.ConfirmPasswordResetRequest;
import com.theninjadev.ajoapi.verification.LoggingSmsSender;
import com.theninjadev.ajoapi.verification.PasswordResetRequest;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.assertj.core.data.TemporalUnitOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Login rate limiting per phone number. Window and block durations differ on purpose, so the
 * tests can tell which one a result came from. Every counting test asserts the persisted row,
 * not just the status: a 401 that rolled its own increment back would pass on status alone.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AdjustableClockConfig.class)
@TestPropertySource(properties = {
        "app.security.login-rate-limit.max-attempts=5",
        "app.security.login-rate-limit.window=20m",
        "app.security.login-rate-limit.block-duration=10m"
})
class LoginRateLimitTest extends AbstractIntegrationTest {

    private static final int MAX_ATTEMPTS = 5;
    private static final Duration WINDOW = Duration.ofMinutes(20);
    private static final Duration BLOCK = Duration.ofMinutes(10);
    private static final String BLOCK_SECONDS = "600";
    // The clock may carry sub-microsecond digits; TIMESTAMPTZ keeps microseconds.
    private static final TemporalUnitOffset STORED_PRECISION = within(1, ChronoUnit.MICROS);

    private static final String PASSWORD = "password123";          // what ApiTestClient registers with
    private static final String WRONG = "wrong-password";
    private static final String NEW_PASSWORD = "brand-new-pass-9";
    private static final Pattern SIX_DIGITS = Pattern.compile("\\b(\\d{6})\\b");

    // Numbers nobody registers: a prefix no other test uses.
    private static final AtomicInteger UNREGISTERED = new AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private LoginAttemptCounterRepository counterRepository;
    @Autowired private AuthService authService;
    @Autowired private LoggingSmsSender smsSender;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;
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
    // The limit works
    // ------------------------------------------------------------------

    @Test
    void failuresUpToTheThresholdAre401AndTheNextIs429() throws Exception {
        var user = verified.registerUser("Ada");

        for (int i = 0; i < MAX_ATTEMPTS; i++)
            login(user.phone(), WRONG).andExpect(status().isUnauthorized());

        login(user.phone(), WRONG).andExpect(status().isTooManyRequests());
    }

    @Test
    void whileBlockedEvenTheCorrectPasswordIs429() throws Exception {
        var user = verified.registerUser("Ada");
        block(user.phone());

        login(user.phone(), PASSWORD).andExpect(status().isTooManyRequests());
    }

    @Test
    void the429CarriesTheConfiguredBlockDurationInHeaderAndBody() throws Exception {
        var user = verified.registerUser("Ada");
        block(user.phone());

        login(user.phone(), WRONG)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", BLOCK_SECONDS))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.detail").value("Too many failed login attempts; try again later"))
                .andExpect(jsonPath("$.instance").value("/auth/login"))
                .andExpect(jsonPath("$.retryAfterSeconds").value(600));
    }

    @Test
    void retryAfterIsTheSameConstantHalfwayThroughTheBlock() throws Exception {
        var user = verified.registerUser("Ada");
        block(user.phone());
        advance(BLOCK.dividedBy(2));

        login(user.phone(), WRONG)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", BLOCK_SECONDS))
                .andExpect(jsonPath("$.retryAfterSeconds").value(600));
    }

    // ------------------------------------------------------------------
    // Persistence — where the rollback trap would show
    // ------------------------------------------------------------------

    @Test
    void eachFailureIsPersistedAndTheBlockingOneResetsTheCountIntoAFreshWindow() throws Exception {
        var user = verified.registerUser("Ada");
        assertThat(counter(user.phone())).isEmpty();

        for (int i = 1; i < MAX_ATTEMPTS; i++) {
            login(user.phone(), WRONG).andExpect(status().isUnauthorized());
            var row = counter(user.phone()).orElseThrow();
            assertThat(row.getFailedCount()).as("after failure %d", i).isEqualTo(i);
            assertThat(row.getBlockedUntil()).as("after failure %d", i).isNull();
        }

        Instant now = Instant.now(clock);
        login(user.phone(), WRONG).andExpect(status().isUnauthorized());

        var blocked = counter(user.phone()).orElseThrow();
        assertThat(blocked.getBlockedUntil()).isCloseTo(now.plus(BLOCK), STORED_PRECISION);
        assertThat(blocked.getFailedCount()).isZero();
        assertThat(blocked.getWindowStartedAt()).isEqualTo(blocked.getBlockedUntil());
    }

    // ------------------------------------------------------------------
    // The block does not extend itself
    // ------------------------------------------------------------------

    @Test
    void hammeringWhileBlockedChangesNothing() throws Exception {
        var user = verified.registerUser("Ada");
        block(user.phone());
        var before = counter(user.phone()).orElseThrow();

        for (int i = 0; i < 10; i++) {
            advance(Duration.ofSeconds(30));
            login(user.phone(), i % 2 == 0 ? WRONG : PASSWORD).andExpect(status().isTooManyRequests());
        }

        var after = counter(user.phone()).orElseThrow();
        assertThat(after.getBlockedUntil()).isEqualTo(before.getBlockedUntil());
        assertThat(after.getFailedCount()).isEqualTo(before.getFailedCount());
        assertThat(after.getWindowStartedAt()).isEqualTo(before.getWindowStartedAt());
    }

    @Test
    void theBlockStillExpiresOnItsOriginalSchedule() throws Exception {
        var user = verified.registerUser("Ada");
        block(user.phone());

        // Hammer through most of the block...
        for (int i = 0; i < 10; i++) {
            advance(Duration.ofSeconds(55));
            login(user.phone(), WRONG).andExpect(status().isTooManyRequests());
        }
        // ...then step just past the original end: 10 × 55s + 51s = 601s > 600s.
        advance(Duration.ofSeconds(51));

        login(user.phone(), PASSWORD).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------
    // Recovery
    // ------------------------------------------------------------------

    @Test
    void afterTheBlockTheCorrectPasswordGetsIn() throws Exception {
        var user = verified.registerUser("Ada");
        block(user.phone());
        advance(BLOCK.plusSeconds(1));

        login(user.phone(), PASSWORD).andExpect(status().isOk());
    }

    @Test
    void afterTheBlockTheNumberHasAFullSetOfAttempts() throws Exception {
        var user = verified.registerUser("Ada");
        block(user.phone());
        advance(BLOCK.plusSeconds(1));

        for (int i = 1; i < MAX_ATTEMPTS; i++)
            login(user.phone(), WRONG).andExpect(status().isUnauthorized());
        assertThat(counter(user.phone()).orElseThrow().getFailedCount()).isEqualTo(MAX_ATTEMPTS - 1);
    }

    @Test
    void failuresOutsideTheWindowDoNotAccumulate() throws Exception {
        var user = verified.registerUser("Ada");

        for (int i = 0; i < MAX_ATTEMPTS - 1; i++)
            login(user.phone(), WRONG).andExpect(status().isUnauthorized());
        advance(WINDOW.plusSeconds(1));
        for (int i = 0; i < MAX_ATTEMPTS - 1; i++)
            login(user.phone(), WRONG).andExpect(status().isUnauthorized());

        var row = counter(user.phone()).orElseThrow();
        assertThat(row.getFailedCount()).isEqualTo(MAX_ATTEMPTS - 1);
        assertThat(row.getBlockedUntil()).isNull();
    }

    // ------------------------------------------------------------------
    // Success clears state
    // ------------------------------------------------------------------

    @Test
    void aSuccessfulLoginDeletesTheRowAndTheNextFailureStartsAtOne() throws Exception {
        var user = verified.registerUser("Ada");
        for (int i = 0; i < 3; i++)
            login(user.phone(), WRONG).andExpect(status().isUnauthorized());

        login(user.phone(), PASSWORD).andExpect(status().isOk());
        assertThat(counter(user.phone())).isEmpty();

        login(user.phone(), WRONG).andExpect(status().isUnauthorized());
        assertThat(counter(user.phone()).orElseThrow().getFailedCount()).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Password reset clears the block
    // ------------------------------------------------------------------

    @Test
    void aCompletedPasswordResetLiftsTheBlockImmediately() throws Exception {
        var user = verified.registerUser("Ada");
        block(user.phone());

        requestReset(user.phone()).andExpect(status().isAccepted());
        confirmReset(user.phone(), sentCode(user), NEW_PASSWORD).andExpect(status().isNoContent());

        assertThat(counter(user.phone())).isEmpty();
        login(user.phone(), NEW_PASSWORD).andExpect(status().isOk());
    }

    @Test
    void aFailedPasswordResetLeavesTheBlockInPlace() throws Exception {
        var user = verified.registerUser("Ada");
        block(user.phone());
        var before = counter(user.phone()).orElseThrow();

        requestReset(user.phone()).andExpect(status().isAccepted());
        confirmReset(user.phone(), wrongCode(sentCode(user)), NEW_PASSWORD).andExpect(status().isBadRequest());

        var after = counter(user.phone()).orElseThrow();
        assertThat(after.getBlockedUntil()).isEqualTo(before.getBlockedUntil());
        login(user.phone(), PASSWORD).andExpect(status().isTooManyRequests());
    }

    // ------------------------------------------------------------------
    // No enumeration
    // ------------------------------------------------------------------

    @Test
    void anUnregisteredNumberIsBlockedOnExactlyTheSameSchedule() throws Exception {
        var user = verified.registerUser("Ada");
        String nobody = unregisteredPhone();

        for (int attempt = 1; attempt <= MAX_ATTEMPTS + 3; attempt++) {
            MockHttpServletResponse known = login(user.phone(), WRONG).andReturn().getResponse();
            MockHttpServletResponse unknown = login(nobody, WRONG).andReturn().getResponse();

            assertThat(unknown.getStatus()).as("status at attempt %d", attempt)
                    .isEqualTo(known.getStatus())
                    .isEqualTo(attempt <= MAX_ATTEMPTS ? 401 : 429);
            assertThat(unknown.getContentAsString()).as("body at attempt %d", attempt)
                    .isEqualTo(known.getContentAsString());
            assertThat(unknown.getHeader("Retry-After")).as("Retry-After at attempt %d", attempt)
                    .isEqualTo(known.getHeader("Retry-After"));
        }

        var knownRow = counter(user.phone()).orElseThrow();
        var unknownRow = counter(toE164(nobody)).orElseThrow();
        assertThat(unknownRow.getFailedCount()).isEqualTo(knownRow.getFailedCount());
        assertThat(unknownRow.getBlockedUntil()).isEqualTo(knownRow.getBlockedUntil());
    }

    @Test
    void anyFormatOfTheSameNumberCountsAgainstOneCounter() throws Exception {
        var user = verified.registerUser("Ada");
        String local = "0" + user.phone().substring(4);          // +234809… → 0809…

        login(local, WRONG).andExpect(status().isUnauthorized());
        login(user.phone(), WRONG).andExpect(status().isUnauthorized());
        login(user.phone().substring(1), WRONG).andExpect(status().isUnauthorized());   // 234809…

        assertThat(counter(user.phone()).orElseThrow().getFailedCount()).isEqualTo(3);
    }

    // ------------------------------------------------------------------
    // Isolation
    // ------------------------------------------------------------------

    @Test
    void blockingOneNumberDoesNotAffectAnother() throws Exception {
        var a = verified.registerUser("Ada");
        var b = verified.registerUser("Bola");
        block(a.phone());

        login(b.phone(), WRONG).andExpect(status().isUnauthorized());
        login(b.phone(), PASSWORD).andExpect(status().isOk());
        login(a.phone(), PASSWORD).andExpect(status().isTooManyRequests());
    }

    @Test
    void aBlockedNumberCanStillRequestAPasswordReset() throws Exception {
        var user = verified.registerUser("Ada");
        block(user.phone());

        requestReset(user.phone()).andExpect(status().isAccepted());
        assertThat(smsSender.lastMessageTo(user.phone())).isPresent();
    }

    @Test
    void aBlockedNumberCanStillRequestPhoneVerification() throws Exception {
        var user = unverified.registerUser("Ada");
        block(user.phone());

        mockMvc.perform(post("/me/phone/verification/request")
                        .header("Authorization", "Bearer " + user.accessToken()))
                .andExpect(status().isAccepted());
    }

    @Test
    void aBlockedNumberCanStillRefreshAValidSession() throws Exception {
        var user = verified.registerUser("Ada");
        Cookie refreshCookie = login(user.phone(), PASSWORD)
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("refresh_token");
        assertThat(refreshCookie).isNotNull();
        block(user.phone());

        mockMvc.perform(post("/auth/refresh").cookie(refreshCookie))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------
    // Concurrency
    // ------------------------------------------------------------------

    @Test
    @Timeout(30)
    void parallelWrongPasswordsLoseNoUpdates() throws Exception {
        var user = verified.registerUser("Ada");
        int n = MAX_ATTEMPTS - 1;

        List<Integer> statuses = runConcurrently(n, () -> loginStatus(user.phone(), WRONG));

        assertThat(statuses).containsOnly(401);
        assertThat(counter(user.phone()).orElseThrow().getFailedCount()).isEqualTo(n);
    }

    @Test
    @Timeout(30)
    void parallelWrongPasswordsPastTheThresholdBlockExactlyOnce() throws Exception {
        var user = verified.registerUser("Ada");
        Instant now = Instant.now(clock);

        List<Integer> statuses = runConcurrently(10, () -> loginStatus(user.phone(), WRONG));

        assertThat(statuses).filteredOn(s -> s == 401).hasSize(MAX_ATTEMPTS);
        assertThat(statuses).filteredOn(s -> s == 429).hasSize(10 - MAX_ATTEMPTS);
        var row = counter(user.phone()).orElseThrow();
        assertThat(row.getBlockedUntil()).isCloseTo(now.plus(BLOCK), STORED_PRECISION);
        assertThat(row.getFailedCount()).isZero();
    }

    /**
     * A success deletes the row between a failure's insertIfAbsent and its findForUpdate. The
     * failure must still be a 401 — not a 500 — and must not resurrect the row.
     * Staged deterministically: this thread holds the row lock, the login's locking read queues
     * behind it, and only then is the row deleted and the lock released.
     */
    @Test
    @Timeout(30)
    void aRowDeletedUnderAWaitingFailureDegradesTo401() throws Exception {
        String nobody = unregisteredPhone();
        String phone = toE164(nobody);
        login(nobody, WRONG).andExpect(status().isUnauthorized());          // the row now exists

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            var tx = new TransactionTemplate(transactionManager);
            Future<Class<? extends Throwable>> outcome = tx.execute(ignored -> {
                assertThat(counterRepository.findForUpdate(phone)).isPresent();

                Future<Class<? extends Throwable>> login = pool.submit(() -> assertThrows(RuntimeException.class,
                        () -> authService.login(new LoginRequest(nobody, WRONG))).getClass());

                awaitLockedRead();
                counterRepository.deleteByPhone(phone);
                return login;                                               // commit releases the lock
            });

            assertThat(outcome.get(10, TimeUnit.SECONDS)).isEqualTo(InvalidCredentialsException.class);
        } finally {
            pool.shutdownNow();
        }

        assertThat(counter(phone)).as("the failure found no row to lock and wrote nothing").isEmpty();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private ResultActions login(String phone, String password) throws Exception {
        return mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest(phone, password))));
    }

    /** Raw status, for worker threads. */
    private int loginStatus(String phone, String password) throws Exception {
        return login(phone, password).andReturn().getResponse().getStatus();
    }

    private void block(String phone) throws Exception {
        for (int i = 0; i < MAX_ATTEMPTS; i++)
            login(phone, WRONG).andExpect(status().isUnauthorized());
        assertThat(counter(normalized(phone)).orElseThrow().getBlockedUntil()).isAfter(Instant.now(clock));
    }

    private ResultActions requestReset(String phone) throws Exception {
        return mockMvc.perform(post("/auth/password-reset/request")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new PasswordResetRequest(phone))));
    }

    private ResultActions confirmReset(String phone, String code, String newPassword) throws Exception {
        return mockMvc.perform(post("/auth/password-reset/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new ConfirmPasswordResetRequest(phone, code, newPassword))));
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

    private Optional<LoginAttemptCounter> counter(String e164Phone) {
        return counterRepository.findById(e164Phone);
    }

    private void advance(Duration duration) {
        ((AdjustableClock) clock).advanceBy(duration);
    }

    /**
     * Waits, boundedly, until the login is queued on the held row lock. pg_stat_activity does not
     * reliably show the waiting statement's text, so this matches any lock wait: nothing else in
     * the test is running. Which statement waited is proved by the outcome — had it been the
     * INSERT, it would have re-created the row once the delete committed.
     */
    private void awaitLockedRead() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject("""
                    select count(*) from pg_stat_activity
                    where datname = current_database() and pid <> pg_backend_pid()
                      and wait_event_type = 'Lock'
                    """, Integer.class);
            if (waiting != null && waiting > 0)
                return;
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for the locking read", e);
            }
        }
        throw new AssertionError("the login never queued behind the held row lock");
    }

    private static String normalized(String phone) {
        return phone.startsWith("+") ? phone : toE164(phone);
    }

    private static String unregisteredPhone() {
        return "0705%07d".formatted(UNREGISTERED.incrementAndGet());
    }

    private static String toE164(String local) {
        return "+234" + local.substring(1);
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
