package com.theninjadev.ajoapi.exit;

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
public class ExitController {

    private final ExitService exitService;

    @PostMapping("/rounds/{roundId}/exit")
    public ResponseEntity<ExitRequestSummary> requestExit(@PathVariable UUID roundId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(exitService.requestExit(currentUserId(), roundId));
    }

    @PostMapping("/exits/{exitId}/cancel")
    public ResponseEntity<ExitRequestSummary> cancelExit(@PathVariable UUID exitId) {
        return ResponseEntity.ok(exitService.cancelExit(currentUserId(), exitId));
    }

    @GetMapping("/rounds/{roundId}/exits")
    public ResponseEntity<List<ExitRequestSummary>> listExitsForRound(@PathVariable UUID roundId) {
        return ResponseEntity.ok(exitService.listExitsForRound(currentUserId(), roundId));
    }

    @GetMapping("/rounds/{roundId}/exit/mine")
    public ResponseEntity<ExitRequestSummary> getMyExit(@PathVariable UUID roundId) {
        return ResponseEntity.ok(exitService.getMyExit(currentUserId(), roundId));
    }

    @PostMapping("/participants/{participantId}/repayments")
    public ResponseEntity<RepaymentSummary> repay(
            @PathVariable UUID participantId,
            @Valid @RequestBody RepayRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(exitService.repay(currentUserId(), participantId, request, idempotencyKey));
    }

    @GetMapping("/participants/{participantId}/repayments")
    public ResponseEntity<List<RepaymentSummary>> listRepayments(@PathVariable UUID participantId) {
        return ResponseEntity.ok(exitService.listRepayments(currentUserId(), participantId));
    }

    @GetMapping("/participants/{participantId}/exposure")
    public ResponseEntity<ExposureSummary> getExposure(@PathVariable UUID participantId) {
        return ResponseEntity.ok(exitService.getExposure(currentUserId(), participantId));
    }

    @PostMapping("/exits/{exitId}/buy-in")
    public ResponseEntity<BuyInSummary> buyIn(
            @PathVariable UUID exitId,
            @Valid @RequestBody BuyInRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(exitService.buyIn(currentUserId(), exitId, request, idempotencyKey));
    }

    @GetMapping("/rounds/{roundId}/buy-ins")
    public ResponseEntity<List<BuyInSummary>> listBuyInsForRound(@PathVariable UUID roundId) {
        return ResponseEntity.ok(exitService.listBuyInsForRound(currentUserId(), roundId));
    }

    @GetMapping("/exits/{exitId}/buy-in")
    public ResponseEntity<BuyInSummary> getBuyInForExit(@PathVariable UUID exitId) {
        return ResponseEntity.ok(exitService.getBuyInForExit(currentUserId(), exitId));
    }

    private UUID currentUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
