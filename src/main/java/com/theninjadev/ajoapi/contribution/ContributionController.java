package com.theninjadev.ajoapi.contribution;

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
@Tag(name = "Contributions", description = "Members paying their monthly share into a cycle, and the round pool those payments fill.")
@AllArgsConstructor
public class ContributionController {

    private final ContributionService contributionService;

    @Operation(summary = "Contribute to a cycle",
            description = "Records one member's payment for one cycle and posts it to the ledger (cash in, pool "
                    + "liability up). The amount must equal the round's agreed amount. Omit userId to pay for "
                    + "yourself; an admin may name another member to record a cash contribution for them. A "
                    + "vacant cycle still accepts contributions until it is settled.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Contribution recorded — or, on a retry with the same Idempotency-Key, the original contribution."),
            @ApiResponse(responseCode = "400", description = "Validation failed, the Idempotency-Key header is missing, the amount is not the round's agreed amount, or the named member is not in this round."),
            @ApiResponse(responseCode = "403", description = "You named another member but are not an admin."),
            @ApiResponse(responseCode = "404", description = "The cycle or round does not exist, you are not a member of the group, or the named user is not a participant."),
            @ApiResponse(responseCode = "409", description = "The round is not active, the cycle has not opened yet, the cycle is already paid out or settled, this member already contributed to this cycle, or the Idempotency-Key was used for a different cycle.")
    })
    @PostMapping("/cycles/{cycleId}/contributions")
    public ResponseEntity<ContributionSummary> contribute(
            @PathVariable UUID cycleId,
            @Valid @RequestBody ContributeRequest request,
            @Parameter(in = ParameterIn.HEADER, name = "Idempotency-Key", required = true,
                    description = "Required — a request without it is rejected with 400. A unique value per contribution (a UUID works). Retrying with the same key returns the original result instead of repeating the operation; reusing a key for a different target is rejected with 409.",
                    example = "9b2e6f0a-4c1d-4e8b-a7f3-2d5c8e1b6a90")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(contributionService.contribute(currentUserId(), cycleId, request, idempotencyKey));
    }

    @Operation(summary = "List a cycle's contributions")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Every contribution to the cycle."),
            @ApiResponse(responseCode = "404", description = "The cycle or round does not exist, or you are not a member of its group.")
    })
    @GetMapping("/cycles/{cycleId}/contributions")
    public ResponseEntity<List<ContributionSummary>> listForCycle(@PathVariable UUID cycleId) {
        return ResponseEntity.ok(contributionService.listForCycle(currentUserId(), cycleId));
    }

    @Operation(summary = "List a round's contributions")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Every contribution across the round's cycles."),
            @ApiResponse(responseCode = "404", description = "The round does not exist, or you are not a member of its group.")
    })
    @GetMapping("/rounds/{roundId}/contributions")
    public ResponseEntity<List<ContributionSummary>> listForRound(@PathVariable UUID roundId) {
        return ResponseEntity.ok(contributionService.listForRound(currentUserId(), roundId));
    }

    @Operation(summary = "List one participant's contributions")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Every contribution that slot has made."),
            @ApiResponse(responseCode = "404", description = "The participant or round does not exist, or you are not a member of its group.")
    })
    @GetMapping("/participants/{participantId}/contributions")
    public ResponseEntity<List<ContributionSummary>> listForParticipant(@PathVariable UUID participantId) {
        return ResponseEntity.ok(contributionService.listForParticipant(currentUserId(), participantId));
    }

    @Operation(summary = "Get the round pool's ledger balance",
            description = "The raw signed ledger balance: the pool is a liability, so money held reads negative.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The pool balance."),
            @ApiResponse(responseCode = "404", description = "The round does not exist, or you are not a member of its group."),
            @ApiResponse(responseCode = "409", description = "The round has not been activated yet, so it has no pool.")
    })
    @GetMapping("/rounds/{roundId}/pool-balance")
    public ResponseEntity<PoolBalance> poolBalance(@PathVariable UUID roundId) {
        return ResponseEntity.ok(contributionService.poolBalance(currentUserId(), roundId));
    }

    private UUID currentUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
