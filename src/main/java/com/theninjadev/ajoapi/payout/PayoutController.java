package com.theninjadev.ajoapi.payout;

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
@AllArgsConstructor
public class    PayoutController {

    private final PayoutService payoutService;

    @PostMapping("/cycles/{cycleId}/payout")
    public ResponseEntity<PayoutSummary> payout(
            @PathVariable UUID cycleId,
            @Valid @RequestBody PayoutRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(payoutService.payout(currentUserId(), cycleId, request, idempotencyKey));
    }

    @GetMapping("/cycles/{cycleId}/payout")
    public ResponseEntity<PayoutSummary> getForCycle(@PathVariable UUID cycleId) {
        return ResponseEntity.ok(payoutService.getForCycle(currentUserId(), cycleId));
    }

    @GetMapping("/rounds/{roundId}/payouts")
    public ResponseEntity<List<PayoutSummary>> listForRound(@PathVariable UUID roundId) {
        return ResponseEntity.ok(payoutService.listForRound(currentUserId(), roundId));
    }

    @GetMapping("/participants/{participantId}/payouts")
    public ResponseEntity<List<PayoutSummary>> listForParticipant(@PathVariable UUID participantId) {
        return ResponseEntity.ok(payoutService.listForParticipant(currentUserId(), participantId));
    }

    @GetMapping("/rounds/{roundId}/shortfall-claims")
    public ResponseEntity<List<ShortfallClaimSummary>> listShortfallClaimsForRound(@PathVariable UUID roundId) {
        return ResponseEntity.ok(payoutService.listShortfallClaimsForRound(currentUserId(), roundId));
    }

    @GetMapping("/rounds/{roundId}/shortfall-claims/mine")
    public ResponseEntity<List<ShortfallClaimSummary>> listMyShortfallClaims(@PathVariable UUID roundId) {
        return ResponseEntity.ok(payoutService.listMyShortfallClaims(currentUserId(), roundId));
    }

    private UUID currentUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
