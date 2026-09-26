package com.theninjadev.ajoapi.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated user endpoints. Kept apart from AuthController, whose endpoints are all public. */
@RestController
@AllArgsConstructor
@Tag(name = "Authentication", description = "Register, log in, and rotate the refresh token that keeps a session alive.")
public class UserController {

    private final AuthService authService;

    @Operation(summary = "Get the current user",
            description = "The user the access token belongs to. A refresh token is not accepted here.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The current user."),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token, or the user no longer exists.")
    })
    @GetMapping("/me")
    public ResponseEntity<UserSummary> me() {
        return ResponseEntity.ok(authService.currentUser(currentUserId()));
    }

    private UUID currentUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
