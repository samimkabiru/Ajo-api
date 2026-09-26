package com.theninjadev.ajoapi.auth;

import com.theninjadev.ajoapi.testsupport.AbstractIntegrationTest;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Two registrations for the same phone (or email) at the same moment: both pass the
 * "already registered?" pre-check, and the unique index decides. The loser must get a 409,
 * not a 500.
 *
 * Transaction A inserts the conflicting row and holds it uncommitted, so register()'s
 * pre-check cannot see it; register() then blocks on the unique index until A commits,
 * and fails with the real constraint violation. Every wait is bounded so a timing problem
 * fails fast instead of hanging CI.
 */
@SpringBootTest
class RegistrationRaceTest extends AbstractIntegrationTest {

    private static final long WAIT_SECONDS = 10;

    @Autowired private AuthService authService;
    @Autowired private UserRepository userRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    @Timeout(30)
    void aPhoneRegisteredConcurrentlyReturnsDuplicatePhone() throws Exception {
        Throwable failure = raceAgainstUncommittedUser(
                user("+2348012348001", null),
                new RegisterRequest("08012348001", "password123", "Second", null));

        assertThat(failure).isInstanceOf(DuplicatePhoneException.class);
    }

    @Test
    @Timeout(30)
    void anEmailRegisteredConcurrentlyReturnsDuplicateEmail() throws Exception {
        // Different case on purpose: the index is on lower(email).
        Throwable failure = raceAgainstUncommittedUser(
                user("+2348012348002", "racer@example.com"),
                new RegisterRequest("08012348003", "password123", "Second", "Racer@Example.com"));

        assertThat(failure).isInstanceOf(DuplicateEmailException.class);
    }

    /** Returns what register() threw once the conflicting row committed underneath it. */
    private Throwable raceAgainstUncommittedUser(User conflicting, RegisterRequest request) throws Exception {
        var definition = new DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        TransactionStatus holder = transactionManager.getTransaction(definition);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            userRepository.saveAndFlush(conflicting);            // inserted, not yet committed

            Future<?> registration = executor.submit(() -> authService.register(request));

            awaitSomeoneBlockedOnALock(registration);
            transactionManager.commit(holder);                    // releases register(), which now conflicts

            try {
                registration.get(WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                return e.getCause();
            }
            return fail("register() succeeded despite the conflicting row");
        } finally {
            if (!holder.isCompleted())
                transactionManager.rollback(holder);
            executor.shutdownNow();
        }
    }

    private void awaitSomeoneBlockedOnALock(Future<?> registration) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (registration.isDone())
                fail("register() finished before blocking on the unique index — the pre-check saw the row?");
            Integer waiting = jdbcTemplate.queryForObject(
                    "select count(*) from pg_stat_activity "
                            + "where datname = current_database() and wait_event_type = 'Lock'",
                    Integer.class);
            if (waiting != null && waiting > 0)
                return;
            Thread.sleep(50);
        }
        fail("register() never blocked on the unique index within " + WAIT_SECONDS + "s");
    }

    private static User user(String phone, String email) {
        Instant now = Instant.now();
        return User.builder()
                .id(UUID.randomUUID())
                .phone(phone)
                .phoneVerified(false)
                .email(email)
                .emailVerified(false)
                .passwordHash("$2a$10$abcdefghijklmnopqrstuuMTJ0gZmZH1v0cqzpH6oZ8yZ3k6ZkZ3S")   // placeholder, never checked
                .fullName("First")
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
