package com.theninjadev.ajoapi.verification;

import com.theninjadev.ajoapi.auth.AuthService;
import com.theninjadev.ajoapi.auth.RegisterRequest;
import com.theninjadev.ajoapi.testsupport.AbstractIntegrationTest;
import com.theninjadev.ajoapi.testsupport.AdjustableClock;
import com.theninjadev.ajoapi.testsupport.AdjustableClockConfig;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Every path through password reset must cost the same: exactly one BCrypt operation, whether
 * the number has an account, whether a code is live, expired, exhausted or wrong, and whether
 * the request was silently rate-limited. Counting BCrypt operations is deterministic where
 * wall-clock timing is not — the same approach as LoginTimingTest.
 */
@SpringBootTest
@Import(AdjustableClockConfig.class)
class PasswordResetTimingTest extends AbstractIntegrationTest {

    /** The real encoder, counting every hash and every comparison. Test-only. */
    static class CountingPasswordEncoder implements PasswordEncoder {
        private final PasswordEncoder delegate = new BCryptPasswordEncoder();
        final AtomicInteger operations = new AtomicInteger();

        @Override
        public String encode(CharSequence rawPassword) {
            operations.incrementAndGet();
            return delegate.encode(rawPassword);
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            operations.incrementAndGet();
            return delegate.matches(rawPassword, encodedPassword);
        }
    }

    @TestConfiguration
    static class CountingEncoderConfig {
        @Bean
        @Primary
        CountingPasswordEncoder countingPasswordEncoder() {
            return new CountingPasswordEncoder();
        }
    }

    private static final Pattern SIX_DIGITS = Pattern.compile("\\b(\\d{6})\\b");
    private static final AtomicInteger PHONES = new AtomicInteger();

    @Autowired private PasswordResetService passwordResetService;
    @Autowired private AuthService authService;
    @Autowired private LoggingSmsSender smsSender;
    @Autowired private CountingPasswordEncoder encoder;
    @Autowired private Clock clock;

    @BeforeEach
    void clearSms() {
        smsSender.clear();
    }

    // ---- request

    @Test
    void anUnknownPhoneRequestCostsOneBcrypt() {
        assertThat(bcryptsDuring(() -> passwordResetService.request(unregisteredPhone()))).isEqualTo(1);
    }

    @Test
    void aKnownPhoneRequestCostsOneBcrypt() {
        String phone = registeredPhone();
        assertThat(bcryptsDuring(() -> passwordResetService.request(phone))).isEqualTo(1);
    }

    @Test
    void aRateLimitedRequestStillCostsOneBcrypt() {
        String phone = registeredPhone();
        passwordResetService.request(phone);

        // Inside the cooldown: nothing is sent, but the work must match the other paths.
        assertThat(bcryptsDuring(() -> passwordResetService.request(phone))).isEqualTo(1);
    }

    // ---- confirm

    @Test
    void confirmForAnUnknownPhoneCostsOneBcrypt() {
        assertThat(failedConfirmCost(unregisteredPhone(), "123456")).isEqualTo(1);
    }

    @Test
    void confirmWithNoLiveCodeCostsOneBcrypt() {
        assertThat(failedConfirmCost(registeredPhone(), "123456")).isEqualTo(1);
    }

    @Test
    void confirmWithAWrongCodeCostsOneBcrypt() {
        String phone = registeredPhone();
        passwordResetService.request(phone);

        assertThat(failedConfirmCost(phone, wrongCode(sentCode(phone)))).isEqualTo(1);
    }

    @Test
    void confirmWithAnExpiredCodeCostsOneBcrypt() {
        String phone = registeredPhone();
        passwordResetService.request(phone);
        String code = sentCode(phone);
        ((AdjustableClock) clock).advanceBy(Duration.ofMinutes(11));

        assertThat(failedConfirmCost(phone, code)).isEqualTo(1);
    }

    @Test
    void confirmAfterAttemptsAreExhaustedCostsOneBcrypt() {
        String phone = registeredPhone();
        passwordResetService.request(phone);
        String code = sentCode(phone);
        for (int i = 0; i < 5; i++)
            failedConfirmCost(phone, wrongCode(code));

        assertThat(failedConfirmCost(phone, code)).isEqualTo(1);
    }

    // ---- helpers

    private int bcryptsDuring(Runnable action) {
        int before = encoder.operations.get();
        action.run();
        return encoder.operations.get() - before;
    }

    private int failedConfirmCost(String phone, String code) {
        return bcryptsDuring(() -> assertThrows(PasswordResetFailedException.class,
                () -> passwordResetService.confirm(phone, code, "brand-new-pass-9")));
    }

    private String registeredPhone() {
        String phone = "0805%07d".formatted(PHONES.incrementAndGet());
        authService.register(new RegisterRequest(phone, "password123", "Timing Tester", null));
        return phone;
    }

    private static String unregisteredPhone() {
        return "0804%07d".formatted(PHONES.incrementAndGet());
    }

    private String sentCode(String localPhone) {
        String message = smsSender.lastMessageTo("+234" + localPhone.substring(1)).orElseThrow();
        Matcher matcher = SIX_DIGITS.matcher(message);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private static String wrongCode(String code) {
        int value = Integer.parseInt(code);
        return Integer.toString(value == 999999 ? 100000 : value + 1);
    }
}
