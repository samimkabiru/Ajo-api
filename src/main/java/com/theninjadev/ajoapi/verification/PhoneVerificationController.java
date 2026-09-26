package com.theninjadev.ajoapi.verification;

import com.theninjadev.ajoapi.auth.UserSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@AllArgsConstructor
@Tag(name = "Phone Verification", description = "Verify the caller's own phone number with a one-time SMS code.")
public class PhoneVerificationController {

    private final OtpService otpService;

    @Operation(summary = "Send a verification code to my phone",
            description = "Texts a six-digit code to the caller's registered number. Requesting again invalidates "
                    + "the previous code. Limited to a few requests an hour, with a short cooldown between them. "
                    + "The code itself is never returned.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Code sent; the body says when it expires and when another may be requested."),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token."),
            @ApiResponse(responseCode = "409", description = "Your phone number is already verified."),
            @ApiResponse(responseCode = "429", description = "Too many codes requested in the last hour, or a code was sent moments ago.")
    })
    @PostMapping("/me/phone/verification/request")
    public ResponseEntity<VerificationCodeRequested> requestCode() {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(otpService.requestCode(currentUserId()));
    }

    @Operation(summary = "Confirm the code and verify my phone",
            description = "A few incorrect attempts invalidate the code; request a new one after that. "
                    + "Verification is required before creating or joining a group.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Phone verified; returns the updated user."),
            @ApiResponse(responseCode = "400", description = "The code is not six digits, or it is not correct."),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token."),
            @ApiResponse(responseCode = "404", description = "No code has been requested, or it is no longer valid."),
            @ApiResponse(responseCode = "409", description = "Your phone number is already verified."),
            @ApiResponse(responseCode = "410", description = "The code has expired; request a new one."),
            @ApiResponse(responseCode = "429", description = "Too many incorrect attempts; the code is now invalid.")
    })
    @PostMapping("/me/phone/verification/confirm")
    public ResponseEntity<UserSummary> confirmCode(@Valid @RequestBody VerifyCodeRequest request) {
        return ResponseEntity.ok(otpService.verifyCode(currentUserId(), request.code()));
    }

    private UUID currentUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
