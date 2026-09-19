package com.theninjadev.ajoapi.round;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@AllArgsConstructor
public class RoundController {

    private final RoundService roundService;

    @PostMapping("/groups/{groupId}/rounds")
    public ResponseEntity<RoundSummary> createRound(@PathVariable UUID groupId, @Valid @RequestBody CreateRoundRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(roundService.createRound(currentUserId(), groupId, request));
    }

    @GetMapping("/groups/{groupId}/rounds")
    public ResponseEntity<List<RoundSummary>> listRoundsForGroup(@PathVariable UUID groupId) {
        return ResponseEntity.ok(roundService.listRoundsForGroup(currentUserId(), groupId));
    }

    @GetMapping("/rounds/{roundId}")
    public ResponseEntity<RoundDetail> getRound(@PathVariable UUID roundId) {
        return ResponseEntity.ok(roundService.getRound(currentUserId(), roundId));
    }

    @PatchMapping("/rounds/{roundId}")
    public ResponseEntity<RoundSummary> updateRound(@PathVariable UUID roundId, @Valid @RequestBody UpdateRoundRequest request) {
        return ResponseEntity.ok(roundService.updateRound(currentUserId(), roundId, request));
    }

    @PostMapping("/rounds/{roundId}/participants")
    public ResponseEntity<ParticipantSummary> addParticipant(@PathVariable UUID roundId, @Valid @RequestBody AddParticipantRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(roundService.addParticipant(currentUserId(), roundId, request));
    }

    @DeleteMapping("/rounds/{roundId}/participants/{userId}")
    public ResponseEntity<Void> removeParticipant(@PathVariable UUID roundId, @PathVariable UUID userId) {
        roundService.removeParticipant(currentUserId(), roundId, userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/rounds/{roundId}/join")
    public ResponseEntity<ParticipantSummary> joinRound(@PathVariable UUID roundId) {
        return ResponseEntity.ok(roundService.joinRound(currentUserId(), roundId));
    }

    @PostMapping("/rounds/{roundId}/leave")
    public ResponseEntity<Void> leaveRound(@PathVariable UUID roundId) {
        roundService.leaveRound(currentUserId(), roundId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/rounds/{roundId}/cancel")
    public ResponseEntity<RoundSummary> cancelRound(@PathVariable UUID roundId) {
        return ResponseEntity.ok(roundService.cancelRound(currentUserId(), roundId));
    }

    @PostMapping("/rounds/{roundId}/activate")
    public ResponseEntity<RoundDetail> activate(@PathVariable UUID roundId) {
        return ResponseEntity.ok(roundService.activate(currentUserId(), roundId));
    }

    private UUID currentUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
