package com.theninjadev.ajoapi.auth;

import io.jsonwebtoken.JwtException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Transactional
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PhoneNumberNormalizer phoneNumberNormalizer;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final Clock clock;
    private final UserMapper userMapper;

    /**
     * Hash compared against when the phone is unknown, so a failed login costs one BCrypt
     * check either way and response time does not reveal which numbers are registered.
     * Made with the same encoder, so it has the same cost factor as real hashes.
     */
    private final String dummyPasswordHash;

    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       PhoneNumberNormalizer phoneNumberNormalizer,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       JwtProperties jwtProperties,
                       Clock clock,
                       UserMapper userMapper) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.phoneNumberNormalizer = phoneNumberNormalizer;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
        this.clock = clock;
        this.userMapper = userMapper;
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public record AuthTokens(String accessToken, String refreshToken, User user) {
    }

    public AuthTokens register(RegisterRequest request) {
        String normalizedPhone = phoneNumberNormalizer.normalize(request.phone());
        String normalizedEmail = normalizeEmail(request.email());

        if (userRepository.findByPhone(normalizedPhone).isPresent())
            throw new DuplicatePhoneException();

        if (normalizedEmail != null && userRepository.findByEmail(normalizedEmail).isPresent())
            throw new DuplicateEmailException();

        Instant now = Instant.now(clock);
        User user = User.builder()
                .id(UUID.randomUUID())
                .phone(normalizedPhone)
                .phoneVerified(false)
                .email(normalizedEmail)
                .emailVerified(false)
                .passwordHash(passwordEncoder.encode(request.password()))
                .fullName(request.fullName())
                .createdAt(now)
                .updatedAt(now)
                .build();

        // The checks above can race with a concurrent registration; the unique indexes are the
        // real guard. Flush now so a violation surfaces here, where it can become a 409.
        try {
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            String constraint = constraintName(e);
            if ("uq_users_phone".equals(constraint))
                throw new DuplicatePhoneException();
            if ("uq_users_email_lower".equals(constraint))
                throw new DuplicateEmailException();
            throw e;
        }

        return issueTokens(user);
    }

    public AuthTokens login(LoginRequest request) {
        String normalizedPhone = phoneNumberNormalizer.normalize(request.phone());

        User user = userRepository.findByPhone(normalizedPhone).orElse(null);

        if (user == null) {
            // Same work as a wrong password, so timing does not reveal whether the phone exists.
            passwordEncoder.matches(request.password(), dummyPasswordHash);
            throw new InvalidCredentialsException();
        }

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash()))
            throw new InvalidCredentialsException();

        return issueTokens(user);
    }

    /** A valid token for a user who no longer exists is treated as an invalid token: 401. */
    @Transactional(readOnly = true)
    public UserSummary currentUser(UUID userId) {
        return userRepository.findById(userId)
                .map(userMapper::toSummary)
                .orElseThrow(() -> new InsufficientAuthenticationException("Authenticated user no longer exists"));
    }

    public AuthTokens refresh(String presentedRefreshToken) {
        UUID userId = validateRefreshTokenClaims(presentedRefreshToken);

        RefreshToken existing = refreshTokenRepository.findByTokenHash(sha256Hex(presentedRefreshToken))
                .orElseThrow(InvalidRefreshTokenException::new);

        Instant now = Instant.now(clock);
        if (existing.getRevokedAt() != null || !existing.getExpiresAt().isAfter(now))
            throw new InvalidRefreshTokenException();

        existing.revoke(now);
        refreshTokenRepository.save(existing);

        User user = userRepository.findById(userId).orElseThrow(InvalidRefreshTokenException::new);
        return issueTokens(user);
    }

    public void logout(String presentedRefreshToken) {
        refreshTokenRepository.findByTokenHash(sha256Hex(presentedRefreshToken))
                .filter(token -> token.getRevokedAt() == null)
                .ifPresent(token -> {
                    token.revoke(Instant.now(clock));
                    refreshTokenRepository.save(token);
                });
    }

    private UUID validateRefreshTokenClaims(String presentedRefreshToken) {
        try {
            var claims = jwtService.parseClaims(presentedRefreshToken);
            if (!JwtService.TOKEN_TYPE_REFRESH.equals(jwtService.extractTokenType(claims)))
                throw new InvalidRefreshTokenException();
            return jwtService.extractUserId(claims);
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidRefreshTokenException();
        }
    }

    private AuthTokens issueTokens(User user) {
        String accessToken = jwtService.generateAccessToken(user.getId());
        String refreshToken = jwtService.generateRefreshToken(user.getId());

        Instant now = Instant.now(clock);
        refreshTokenRepository.save(RefreshToken.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .tokenHash(sha256Hex(refreshToken))
                .expiresAt(now.plus(jwtProperties.refreshTokenTtlDays(), ChronoUnit.DAYS))
                .createdAt(now)
                .build());

        return new AuthTokens(accessToken, refreshToken, user);
    }

    /** Trimmed and lowercased; blank means no email. */
    static String normalizeEmail(String email) {
        return StringUtils.hasText(email) ? email.trim().toLowerCase(Locale.ROOT) : null;
    }

    /** The violated constraint's name, from Hibernate if it knows it, else from the driver's message. */
    private static String constraintName(DataIntegrityViolationException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve && cve.getConstraintName() != null)
                return cve.getConstraintName().toLowerCase(Locale.ROOT);
        }
        String message = String.valueOf(e.getMostSpecificCause().getMessage());
        if (message.contains("uq_users_phone")) return "uq_users_phone";
        if (message.contains("uq_users_email_lower")) return "uq_users_email_lower";
        return null;
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
