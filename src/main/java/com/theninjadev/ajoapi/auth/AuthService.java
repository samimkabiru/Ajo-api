package com.theninjadev.ajoapi.auth;

import io.jsonwebtoken.JwtException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;
import lombok.AllArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@AllArgsConstructor
@Transactional
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PhoneNumberNormalizer phoneNumberNormalizer;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final Clock clock;

    public record AuthTokens(String accessToken, String refreshToken, User user) {
    }

    public AuthTokens register(RegisterRequest request) {
        String normalizedPhone = phoneNumberNormalizer.normalize(request.phone());

        if (userRepository.findByPhone(normalizedPhone).isPresent())
            throw new DuplicatePhoneException();

        if (StringUtils.hasText(request.email()) && userRepository.findByEmail(request.email()).isPresent())
            throw new DuplicateEmailException();

        Instant now = Instant.now(clock);
        User user = userRepository.save(User.builder()
                .id(UUID.randomUUID())
                .phone(normalizedPhone)
                .phoneVerified(false)
                .email(StringUtils.hasText(request.email()) ? request.email() : null)
                .emailVerified(false)
                .passwordHash(passwordEncoder.encode(request.password()))
                .fullName(request.fullName())
                .createdAt(now)
                .updatedAt(now)
                .build());

        return issueTokens(user);
    }

    public AuthTokens login(LoginRequest request) {
        String normalizedPhone = phoneNumberNormalizer.normalize(request.phone());

        User user = userRepository.findByPhone(normalizedPhone)
                .orElseThrow(InvalidCredentialsException::new);

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash()))
            throw new InvalidCredentialsException();

        return issueTokens(user);
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
