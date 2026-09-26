package com.theninjadev.ajoapi.auth;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** Every session still alive for a user — all of them are revoked on a password reset. */
    List<RefreshToken> findByUserIdAndRevokedAtIsNull(UUID userId);
}
