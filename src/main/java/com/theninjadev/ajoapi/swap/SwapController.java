package com.theninjadev.ajoapi.swap;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Position Swaps", description = "Two members trading payout slots by mutual consent. Only collection timing changes, never what anyone pays.")
@AllArgsConstructor
public class SwapController {

    private final SwapService swapService;

    @Operation(summary = "Ask another member to trade payout positions",
            description = "Both members must be active and neither may have collected yet. The swap may not move a "
                    + "member in their first round in this group ahead of one who has completed a round. You may "
                    + "have only one pending outgoing request per round.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Swap request sent."),
            @ApiResponse(responseCode = "400", description = "Validation failed, or you asked to swap with yourself."),
            @ApiResponse(responseCode = "404", description = "The round does not exist, you are not a member of its group, or you or the target are not participants."),
            @ApiResponse(responseCode = "409", description = "The round is not active, you or the target are no longer active or have already collected, you already have a pending request, or the swap would move a member in their first round ahead of one who has completed a round.")
    })
    @PostMapping("/rounds/{roundId}/swaps")
    public ResponseEntity<SwapRequestSummary> requestSwap(
            @PathVariable UUID roundId, @Valid @RequestBody CreateSwapRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(swapService.requestSwap(currentUserId(), roundId, request));
    }

    @Operation(summary = "List every swap request in a round",
            description = "Full history, in every status.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "All swap requests in the round."),
            @ApiResponse(responseCode = "404", description = "The round does not exist, or you are not a member of its group.")
    })
    @GetMapping("/rounds/{roundId}/swaps")
    public ResponseEntity<List<SwapRequestSummary>> listForRound(@PathVariable UUID roundId) {
        return ResponseEntity.ok(swapService.listForRound(currentUserId(), roundId));
    }

    @Operation(summary = "List pending requests sent to me")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Pending requests where you are the target."),
            @ApiResponse(responseCode = "404", description = "The round does not exist, or you are not a participant in it.")
    })
    @GetMapping("/rounds/{roundId}/swaps/incoming")
    public ResponseEntity<List<SwapRequestSummary>> listIncoming(@PathVariable UUID roundId) {
        return ResponseEntity.ok(swapService.listMyIncoming(currentUserId(), roundId));
    }

    @Operation(summary = "List my pending outgoing requests")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Pending requests you made."),
            @ApiResponse(responseCode = "404", description = "The round does not exist, or you are not a participant in it.")
    })
    @GetMapping("/rounds/{roundId}/swaps/outgoing")
    public ResponseEntity<List<SwapRequestSummary>> listOutgoing(@PathVariable UUID roundId) {
        return ResponseEntity.ok(swapService.listMyOutgoing(currentUserId(), roundId));
    }

    @Operation(summary = "Accept a swap request",
            description = "Exchanges the two members' payout positions and cycles atomically. Any other pending "
                    + "request involving either member is superseded.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Swap accepted; positions exchanged."),
            @ApiResponse(responseCode = "403", description = "Only the member the request was sent to can accept it."),
            @ApiResponse(responseCode = "404", description = "The swap request or its round does not exist."),
            @ApiResponse(responseCode = "409", description = "The request is no longer pending, or a payout position changed since it was made.")
    })
    @PostMapping("/swaps/{swapId}/accept")
    public ResponseEntity<SwapRequestSummary> accept(@PathVariable UUID swapId) {
        return ResponseEntity.ok(swapService.accept(currentUserId(), swapId));
    }

    @Operation(summary = "Decline a swap request")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Swap declined."),
            @ApiResponse(responseCode = "403", description = "Only the member the request was sent to can decline it."),
            @ApiResponse(responseCode = "404", description = "The swap request does not exist."),
            @ApiResponse(responseCode = "409", description = "The request is no longer pending.")
    })
    @PostMapping("/swaps/{swapId}/decline")
    public ResponseEntity<SwapRequestSummary> decline(@PathVariable UUID swapId) {
        return ResponseEntity.ok(swapService.decline(currentUserId(), swapId));
    }

    @Operation(summary = "Cancel your own swap request")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Swap cancelled."),
            @ApiResponse(responseCode = "403", description = "Only the member who made the request can cancel it."),
            @ApiResponse(responseCode = "404", description = "The swap request does not exist."),
            @ApiResponse(responseCode = "409", description = "The request is no longer pending.")
    })
    @PostMapping("/swaps/{swapId}/cancel")
    public ResponseEntity<SwapRequestSummary> cancel(@PathVariable UUID swapId) {
        return ResponseEntity.ok(swapService.cancel(currentUserId(), swapId));
    }

    private UUID currentUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
