package com.theninjadev.ajoapi.verification;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@AllArgsConstructor
@Tag(name = "Password Reset", description = "Reset a forgotten password with a one-time SMS code. Reveals nothing about which numbers have accounts.")
@SecurityRequirements   // public: the caller cannot log in
public class PasswordResetController {

    private final PasswordResetService passwordResetService;

    @Operation(summary = "Request a password reset code",
            description = "Always returns the same 202 and body, whether or not the number has an account, and "
                    + "whether or not a code was actually sent (rate limits apply silently). The body carries "
                    + "the configured code lifetime and resend cooldown.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Accepted. If the number has an account, a code has been texted to it."),
            @ApiResponse(responseCode = "400", description = "Validation failed, or the phone number is not a valid Nigerian number.")
    })
    @PostMapping("/auth/password-reset/request")
    public ResponseEntity<PasswordResetRequested> request(@Valid @RequestBody PasswordResetRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(passwordResetService.request(request.phone()));
    }

    @Operation(summary = "Set a new password with the code",
            description = "Every failure gives the same 400. On success every session is ended — all refresh "
                    + "tokens are revoked — and no tokens are returned: log in with the new password.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Password changed; all sessions ended."),
            @ApiResponse(responseCode = "400", description = "The reset code is not valid, or validation failed "
                    + "(six-digit code; new password of at least 8 characters and at most 72 bytes).")
    })
    @PostMapping("/auth/password-reset/confirm")
    public ResponseEntity<Void> confirm(@Valid @RequestBody ConfirmPasswordResetRequest request) {
        passwordResetService.confirm(request.phone(), request.code(), request.newPassword());
        return ResponseEntity.noContent().build();
    }
}
