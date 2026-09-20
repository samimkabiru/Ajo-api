package com.theninjadev.ajoapi.contribution;

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
public class ContributionController {

    private final ContributionService contributionService;

    @PostMapping("/cycles/{cycleId}/contributions")
    public ResponseEntity<ContributionSummary> contribute(
            @PathVariable UUID cycleId,
            @Valid @RequestBody ContributeRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(contributionService.contribute(currentUserId(), cycleId, request, idempotencyKey));
    }

    @GetMapping("/cycles/{cycleId}/contributions")
    public ResponseEntity<List<ContributionSummary>> listForCycle(@PathVariable UUID cycleId) {
        return ResponseEntity.ok(contributionService.listForCycle(currentUserId(), cycleId));
    }

    @GetMapping("/rounds/{roundId}/contributions")
    public ResponseEntity<List<ContributionSummary>> listForRound(@PathVariable UUID roundId) {
        return ResponseEntity.ok(contributionService.listForRound(currentUserId(), roundId));
    }

    @GetMapping("/participants/{participantId}/contributions")
    public ResponseEntity<List<ContributionSummary>> listForParticipant(@PathVariable UUID participantId) {
        return ResponseEntity.ok(contributionService.listForParticipant(currentUserId(), participantId));
    }

    @GetMapping("/rounds/{roundId}/pool-balance")
    public ResponseEntity<PoolBalance> poolBalance(@PathVariable UUID roundId) {
        return ResponseEntity.ok(contributionService.poolBalance(currentUserId(), roundId));
    }

    private UUID currentUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
