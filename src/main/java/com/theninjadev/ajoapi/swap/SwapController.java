package com.theninjadev.ajoapi.swap;

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
import org.springframework.web.bind.annotation.RestController;

@RestController
@AllArgsConstructor
public class SwapController {

    private final SwapService swapService;

    @PostMapping("/rounds/{roundId}/swaps")
    public ResponseEntity<SwapRequestSummary> requestSwap(
            @PathVariable UUID roundId, @Valid @RequestBody CreateSwapRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(swapService.requestSwap(currentUserId(), roundId, request));
    }

    @GetMapping("/rounds/{roundId}/swaps")
    public ResponseEntity<List<SwapRequestSummary>> listForRound(@PathVariable UUID roundId) {
        return ResponseEntity.ok(swapService.listForRound(currentUserId(), roundId));
    }

    @GetMapping("/rounds/{roundId}/swaps/incoming")
    public ResponseEntity<List<SwapRequestSummary>> listIncoming(@PathVariable UUID roundId) {
        return ResponseEntity.ok(swapService.listMyIncoming(currentUserId(), roundId));
    }

    @GetMapping("/rounds/{roundId}/swaps/outgoing")
    public ResponseEntity<List<SwapRequestSummary>> listOutgoing(@PathVariable UUID roundId) {
        return ResponseEntity.ok(swapService.listMyOutgoing(currentUserId(), roundId));
    }

    @PostMapping("/swaps/{swapId}/accept")
    public ResponseEntity<SwapRequestSummary> accept(@PathVariable UUID swapId) {
        return ResponseEntity.ok(swapService.accept(currentUserId(), swapId));
    }

    @PostMapping("/swaps/{swapId}/decline")
    public ResponseEntity<SwapRequestSummary> decline(@PathVariable UUID swapId) {
        return ResponseEntity.ok(swapService.decline(currentUserId(), swapId));
    }

    @PostMapping("/swaps/{swapId}/cancel")
    public ResponseEntity<SwapRequestSummary> cancel(@PathVariable UUID swapId) {
        return ResponseEntity.ok(swapService.cancel(currentUserId(), swapId));
    }

    private UUID currentUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
