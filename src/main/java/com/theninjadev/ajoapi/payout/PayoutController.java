package com.theninjadev.ajoapi.payout;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Payouts", description = "A cycle's beneficiary collecting the pot, and the shortfall claims raised when it comes up short.")
@AllArgsConstructor
public class    PayoutController {

    private final PayoutService payoutService;

    @Operation(summary = "Pay out a cycle to its beneficiary",
            description = "Pays the beneficiary what the pool actually holds, capped at the full pot — the platform "
                    + "never pays out money it did not receive. If the pot is short because others missed, a "
                    + "shortfall claim is raised for the difference. The beneficiary's own earlier missed "
                    + "contributions are withheld from the payout and used to settle the claims they caused; if "
                    + "that consumes everything, the payout is zero and the cycle is still marked PAID. The "
                    + "amount is never client-supplied. The beneficiary, or a group admin, may record it.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Payout recorded — or, on a retry with the same Idempotency-Key, the original payout."),
            @ApiResponse(responseCode = "400", description = "Validation failed, or the Idempotency-Key header is missing."),
            @ApiResponse(responseCode = "403", description = "You are neither the cycle's beneficiary nor a group admin."),
            @ApiResponse(responseCode = "404", description = "The cycle or round does not exist, or you are not a member of its group."),
            @ApiResponse(responseCode = "409", description = "The cycle is already paid out, the payout date has not arrived, the pool is empty, the beneficiary changed since you loaded it (expectedBeneficiaryUserId no longer matches), the beneficiary is leaving the round, the cycle has no beneficiary, the round is not active, or the Idempotency-Key was used for a different cycle.")
    })
    @PostMapping("/cycles/{cycleId}/payout")
    public ResponseEntity<PayoutSummary> payout(
            @PathVariable UUID cycleId,
            @Valid @RequestBody PayoutRequest request,
            @Parameter(in = ParameterIn.HEADER, name = "Idempotency-Key", required = true,
                    description = "Required — a request without it is rejected with 400. A unique value per payout (a UUID works). Retrying with the same key returns the original result instead of repeating the operation; reusing a key for a different target is rejected with 409.",
                    example = "9b2e6f0a-4c1d-4e8b-a7f3-2d5c8e1b6a90")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(payoutService.payout(currentUserId(), cycleId, request, idempotencyKey));
    }

    @Operation(summary = "Get a cycle's payout")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The payout."),
            @ApiResponse(responseCode = "404", description = "The cycle or round does not exist, you are not a member of its group, or the cycle has not been paid out.")
    })
    @GetMapping("/cycles/{cycleId}/payout")
    public ResponseEntity<PayoutSummary> getForCycle(@PathVariable UUID cycleId) {
        return ResponseEntity.ok(payoutService.getForCycle(currentUserId(), cycleId));
    }

    @Operation(summary = "List a round's payouts")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Every payout made in the round."),
            @ApiResponse(responseCode = "404", description = "The round does not exist, or you are not a member of its group.")
    })
    @GetMapping("/rounds/{roundId}/payouts")
    public ResponseEntity<List<PayoutSummary>> listForRound(@PathVariable UUID roundId) {
        return ResponseEntity.ok(payoutService.listForRound(currentUserId(), roundId));
    }

    @Operation(summary = "List one participant's payouts")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Payouts made to that slot."),
            @ApiResponse(responseCode = "404", description = "The participant or round does not exist, or you are not a member of its group.")
    })
    @GetMapping("/participants/{participantId}/payouts")
    public ResponseEntity<List<PayoutSummary>> listForParticipant(@PathVariable UUID participantId) {
        return ResponseEntity.ok(payoutService.listForParticipant(currentUserId(), participantId));
    }

    @Operation(summary = "List a round's shortfall claims",
            description = "All claims, whether open, part-paid or settled.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Every shortfall claim in the round."),
            @ApiResponse(responseCode = "404", description = "The round does not exist, or you are not a member of its group.")
    })
    @GetMapping("/rounds/{roundId}/shortfall-claims")
    public ResponseEntity<List<ShortfallClaimSummary>> listShortfallClaimsForRound(@PathVariable UUID roundId) {
        return ResponseEntity.ok(payoutService.listShortfallClaimsForRound(currentUserId(), roundId));
    }

    @Operation(summary = "List my unsettled shortfall claims")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Claims in your favour that are not yet fully settled."),
            @ApiResponse(responseCode = "404", description = "The round does not exist, or you are not a participant in it.")
    })
    @GetMapping("/rounds/{roundId}/shortfall-claims/mine")
    public ResponseEntity<List<ShortfallClaimSummary>> listMyShortfallClaims(@PathVariable UUID roundId) {
        return ResponseEntity.ok(payoutService.listMyShortfallClaims(currentUserId(), roundId));
    }

    private UUID currentUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
