package com.theninjadev.ajoapi.exit;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import com.theninjadev.ajoapi.payout.ShortfallClaimSummary;
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
@Tag(name = "Exits & Settlement", description = "Leaving an active round: exposure, repayment, buy-ins, and settling a vacant cycle's pot into refunds and shortfall claims.")
@AllArgsConstructor
public class ExitController {

    private final ExitService exitService;

    @Operation(summary = "Request to leave an active round",
            description = "Outcome depends on exposure (collected − contributed). At zero you leave immediately "
                    + "(COMPLETED) and, if you have not collected, your cycle becomes VACANT. Otherwise the exit "
                    + "is PENDING_SETTLEMENT and you keep your cycle and position: if you owe the group you stay "
                    + "liable until you repay; if the group owes you, you are refunded when your vacant cycle is "
                    + "settled, or immediately if a replacement buys in. Consider a position swap first.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Exit recorded — COMPLETED if exposure was zero, otherwise PENDING_SETTLEMENT."),
            @ApiResponse(responseCode = "404", description = "The round does not exist, you are not a member of its group, or you are not a participant."),
            @ApiResponse(responseCode = "409", description = "The round is not active, you are no longer active in it, or you already have an exit in progress.")
    })
    @PostMapping("/rounds/{roundId}/exit")
    public ResponseEntity<ExitRequestSummary> requestExit(@PathVariable UUID roundId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(exitService.requestExit(currentUserId(), roundId));
    }

    @Operation(summary = "Cancel your pending exit",
            description = "You become ACTIVE again with your cycle and position untouched.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Exit cancelled."),
            @ApiResponse(responseCode = "403", description = "Only the member who requested the exit can cancel it."),
            @ApiResponse(responseCode = "404", description = "The exit request does not exist."),
            @ApiResponse(responseCode = "409", description = "The exit has already been completed or cancelled.")
    })
    @PostMapping("/exits/{exitId}/cancel")
    public ResponseEntity<ExitRequestSummary> cancelExit(@PathVariable UUID exitId) {
        return ResponseEntity.ok(exitService.cancelExit(currentUserId(), exitId));
    }

    @Operation(summary = "List a round's exit requests",
            description = "Every status.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "All exit requests in the round."),
            @ApiResponse(responseCode = "404", description = "The round does not exist, or you are not a member of its group.")
    })
    @GetMapping("/rounds/{roundId}/exits")
    public ResponseEntity<List<ExitRequestSummary>> listExitsForRound(@PathVariable UUID roundId) {
        return ResponseEntity.ok(exitService.listExitsForRound(currentUserId(), roundId));
    }

    @Operation(summary = "Get my open exit request")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Your PENDING_SETTLEMENT exit."),
            @ApiResponse(responseCode = "404", description = "The round does not exist, you are not a participant, or you have no open exit.")
    })
    @GetMapping("/rounds/{roundId}/exit/mine")
    public ResponseEntity<ExitRequestSummary> getMyExit(@PathVariable UUID roundId) {
        return ResponseEntity.ok(exitService.getMyExit(currentUserId(), roundId));
    }

    @Operation(summary = "Repay money owed to the group",
            description = "For a member who has collected more than they contributed. Partial repayments are accepted "
                    + "and walk the debt down; clearing it completes a pending exit. The debtor repays for "
                    + "themselves, or an admin records a cash repayment for them. Allowed while the round is "
                    + "ACTIVE or after it COMPLETED.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Repayment recorded — or, on a retry with the same Idempotency-Key, the original repayment."),
            @ApiResponse(responseCode = "400", description = "Validation failed, the Idempotency-Key header is missing, or the amount is more than is owed."),
            @ApiResponse(responseCode = "403", description = "You are repaying for someone else but are not an admin."),
            @ApiResponse(responseCode = "404", description = "The participant or round does not exist, or you are not a member of its group."),
            @ApiResponse(responseCode = "409", description = "The round is neither active nor completed, nothing is owed, or the Idempotency-Key was used for a different participant.")
    })
    @PostMapping("/participants/{participantId}/repayments")
    public ResponseEntity<RepaymentSummary> repay(
            @PathVariable UUID participantId,
            @Valid @RequestBody RepayRequest request,
            @Parameter(in = ParameterIn.HEADER, name = "Idempotency-Key", required = true,
                    description = "Required — a request without it is rejected with 400. A unique value per repayment or buy-in (a UUID works). Retrying with the same key returns the original result instead of repeating the operation; reusing a key for a different target is rejected with 409.",
                    example = "9b2e6f0a-4c1d-4e8b-a7f3-2d5c8e1b6a90")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(exitService.repay(currentUserId(), participantId, request, idempotencyKey));
    }

    @Operation(summary = "List a participant's repayments")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Repayments made by that slot."),
            @ApiResponse(responseCode = "404", description = "The participant or round does not exist, or you are not a member of its group.")
    })
    @GetMapping("/participants/{participantId}/repayments")
    public ResponseEntity<List<RepaymentSummary>> listRepayments(@PathVariable UUID participantId) {
        return ResponseEntity.ok(exitService.listRepayments(currentUserId(), participantId));
    }

    @Operation(summary = "Get a participant's exposure",
            description = "Derived from the ledger: collected + refunded + claim settlements − contributed − repaid. "
                    + "Positive means they owe the group; negative means the group owes them.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Current exposure."),
            @ApiResponse(responseCode = "404", description = "The participant or round does not exist, or you are not a member of its group.")
    })
    @GetMapping("/participants/{participantId}/exposure")
    public ResponseEntity<ExposureSummary> getExposure(@PathVariable UUID participantId) {
        return ResponseEntity.ok(exitService.getExposure(currentUserId(), participantId));
    }

    @Operation(summary = "Buy into a leaving member's position",
            description = "A replacement pays exactly the leaver's contributed total into the pool, and the leaver is "
                    + "refunded the same amount immediately. The slot — position, cycle and contribution history "
                    + "— passes to the replacement, so the schedule is untouched and the pool nets to zero. Only "
                    + "for a leaver the group owes who has not yet collected. The replacement records it "
                    + "themselves, or an admin records it for them.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Buy-in recorded — or, on a retry with the same Idempotency-Key, the original buy-in."),
            @ApiResponse(responseCode = "400", description = "Validation failed, the Idempotency-Key header is missing, the amount does not match the leaver's contributed total, or the replacement is not a member of the group."),
            @ApiResponse(responseCode = "403", description = "You are neither the replacement nor a group admin."),
            @ApiResponse(responseCode = "404", description = "The exit request or its round does not exist, or you are not a member of the group."),
            @ApiResponse(responseCode = "409", description = "The exit is not pending or already settled, the leaver owes the group or has already collected, the replacement is already in this round, the round is not active, or the Idempotency-Key was used for a different exit.")
    })
    @PostMapping("/exits/{exitId}/buy-in")
    public ResponseEntity<BuyInSummary> buyIn(
            @PathVariable UUID exitId,
            @Valid @RequestBody BuyInRequest request,
            @Parameter(in = ParameterIn.HEADER, name = "Idempotency-Key", required = true,
                    description = "Required — a request without it is rejected with 400. A unique value per repayment or buy-in (a UUID works). Retrying with the same key returns the original result instead of repeating the operation; reusing a key for a different target is rejected with 409.",
                    example = "9b2e6f0a-4c1d-4e8b-a7f3-2d5c8e1b6a90")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(exitService.buyIn(currentUserId(), exitId, request, idempotencyKey));
    }

    @Operation(summary = "List a round's buy-ins")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Every buy-in in the round."),
            @ApiResponse(responseCode = "404", description = "The round does not exist, or you are not a member of its group.")
    })
    @GetMapping("/rounds/{roundId}/buy-ins")
    public ResponseEntity<List<BuyInSummary>> listBuyInsForRound(@PathVariable UUID roundId) {
        return ResponseEntity.ok(exitService.listBuyInsForRound(currentUserId(), roundId));
    }

    @Operation(summary = "Get the buy-in that settled an exit")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The buy-in."),
            @ApiResponse(responseCode = "404", description = "The exit request or its round does not exist, or you are not a member of the group. Also returned when no buy-in settled this exit.")
    })
    @GetMapping("/exits/{exitId}/buy-in")
    public ResponseEntity<BuyInSummary> getBuyInForExit(@PathVariable UUID exitId) {
        return ResponseEntity.ok(exitService.getBuyInForExit(currentUserId(), exitId));
    }

    @Operation(summary = "Settle a vacant cycle",
            description = "Distributes the pot a vacant cycle collected: the leaver's refund first, then open "
                    + "shortfall claims oldest first. Can run again as later claims arrive; the cycle becomes "
                    + "SETTLED only when nothing is outstanding. A cycle whose beneficiary has a pending exit is "
                    + "vacated on the way in. Admins only.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Settlement pass completed."),
            @ApiResponse(responseCode = "403", description = "You are not a group admin."),
            @ApiResponse(responseCode = "404", description = "The cycle or round does not exist, or you are not a member of its group."),
            @ApiResponse(responseCode = "409", description = "The cycle is not vacant, is already fully settled, has not reached its payout date, or has nothing left to distribute.")
    })
    @PostMapping("/cycles/{cycleId}/settle")
    public ResponseEntity<SettlementSummary> settleVacantCycle(@PathVariable UUID cycleId) {
        return ResponseEntity.ok(exitService.settleVacantCycle(currentUserId(), cycleId));
    }

    @Operation(summary = "Preview a vacant cycle's settlement")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The pot, the refund owed, open claims, and whether settlement can run now."),
            @ApiResponse(responseCode = "404", description = "The cycle or round does not exist, or you are not a member of its group."),
            @ApiResponse(responseCode = "409", description = "The cycle is neither vacant nor settled.")
    })
    @GetMapping("/cycles/{cycleId}/settlement")
    public ResponseEntity<VacantCycleSummary> getVacantCycleStatus(@PathVariable UUID cycleId) {
        return ResponseEntity.ok(exitService.getVacantCycleStatus(currentUserId(), cycleId));
    }

    @Operation(summary = "List a round's refunds")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Every refund paid from a vacant cycle in the round."),
            @ApiResponse(responseCode = "404", description = "The round does not exist, or you are not a member of its group.")
    })
    @GetMapping("/rounds/{roundId}/refunds")
    public ResponseEntity<List<RefundSummary>> listRefundsForRound(@PathVariable UUID roundId) {
        return ResponseEntity.ok(exitService.listRefundsForRound(currentUserId(), roundId));
    }

    @Operation(summary = "Get the refund paid for an exit")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The refund."),
            @ApiResponse(responseCode = "404", description = "The exit request or its round does not exist, or you are not a member of the group. Also returned when no refund has been paid for this exit yet.")
    })
    @GetMapping("/exits/{exitId}/refund")
    public ResponseEntity<RefundSummary> getRefundForExit(@PathVariable UUID exitId) {
        return ResponseEntity.ok(exitService.getRefundForExit(currentUserId(), exitId));
    }

    @Operation(summary = "List a round's open shortfall claims",
            description = "Not fully settled, oldest first — the order a vacant pot or withheld arrears pay them in.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Open and part-paid claims."),
            @ApiResponse(responseCode = "404", description = "The round does not exist, or you are not a member of its group.")
    })
    @GetMapping("/rounds/{roundId}/shortfall-claims/open")
    public ResponseEntity<List<ShortfallClaimSummary>> listOpenShortfallClaims(@PathVariable UUID roundId) {
        return ResponseEntity.ok(exitService.listOpenShortfallClaims(currentUserId(), roundId));
    }

    private UUID currentUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
