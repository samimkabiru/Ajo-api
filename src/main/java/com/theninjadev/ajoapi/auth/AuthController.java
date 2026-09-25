package com.theninjadev.ajoapi.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Duration;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@Tag(name = "Authentication", description = "Register, log in, and rotate the refresh token that keeps a session alive.")
@SecurityRequirements   // public: no access token needed
@AllArgsConstructor
public class AuthController {

    private static final String REFRESH_TOKEN_COOKIE = "refresh_token";

    private final AuthService authService;
    private final UserMapper userMapper;
    private final JwtProperties jwtProperties;

    @Operation(summary = "Register a new user",
            description = "Creates the account and logs it in: returns an access token and sets the "
                    + "refresh token as an HttpOnly cookie scoped to /auth.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Registered and logged in."),
            @ApiResponse(responseCode = "400", description = "Validation failed, or the phone number is not a valid Nigerian number."),
            @ApiResponse(responseCode = "409", description = "The phone number or email is already registered.")
    })
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        var tokens = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookie(tokens.refreshToken()).toString())
                .body(toResponse(tokens));
    }

    @Operation(summary = "Log in with phone number and password",
            description = "Returns an access token and sets a fresh refresh-token cookie.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Logged in."),
            @ApiResponse(responseCode = "400", description = "Validation failed, or the phone number is not a valid Nigerian number."),
            @ApiResponse(responseCode = "401", description = "Wrong phone number or password.")
    })
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        var tokens = authService.login(request);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookie(tokens.refreshToken()).toString())
                .body(toResponse(tokens));
    }

    @Operation(summary = "Exchange the refresh cookie for a new access token",
            description = "Rotates the refresh token: the presented one is revoked and a new cookie is set. "
                    + "Reads the refresh_token cookie, not a header.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "New access token issued and refresh cookie rotated."),
            @ApiResponse(responseCode = "400", description = "The refresh cookie is missing, invalid, expired or already used.")
    })
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@CookieValue(REFRESH_TOKEN_COOKIE) String refreshToken) {
        var tokens = authService.refresh(refreshToken);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookie(tokens.refreshToken()).toString())
                .body(toResponse(tokens));
    }

    @Operation(summary = "Log out",
            description = "Revokes the presented refresh token and clears the cookie. Access tokens already "
                    + "issued stay valid until they expire.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Logged out and cookie cleared."),
            @ApiResponse(responseCode = "400", description = "The refresh cookie is missing.")
    })
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(REFRESH_TOKEN_COOKIE) String refreshToken) {
        authService.logout(refreshToken);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, clearedRefreshTokenCookie().toString())
                .build();
    }

    private AuthResponse toResponse(AuthService.AuthTokens tokens) {
        return new AuthResponse(tokens.accessToken(), userMapper.toSummary(tokens.user()));
    }

    private ResponseCookie refreshTokenCookie(String refreshToken) {
        return ResponseCookie.from(REFRESH_TOKEN_COOKIE, refreshToken)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path("/auth")
                .maxAge(Duration.ofDays(jwtProperties.refreshTokenTtlDays()))
                .build();
    }

    private ResponseCookie clearedRefreshTokenCookie() {
        return ResponseCookie.from(REFRESH_TOKEN_COOKIE, "")
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path("/auth")
                .maxAge(0)
                .build();
    }
}
