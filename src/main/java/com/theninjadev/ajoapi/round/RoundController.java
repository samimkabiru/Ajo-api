package com.theninjadev.ajoapi.round;

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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Rounds", description = "One full rotation with fixed terms: form it, add participants, then activate to fix the schedule.")
@AllArgsConstructor
public class RoundController {

    private final RoundService roundService;

    @Operation(summary = "Create a round",
            description = "Starts in FORMING with the agreed monthly amount. New terms always mean a new round, never "
                    + "a changed one. A group can have only one FORMING or ACTIVE round at a time.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Round created in FORMING."),
            @ApiResponse(responseCode = "400", description = "Validation failed."),
            @ApiResponse(responseCode = "403", description = "You are not an admin of this group."),
            @ApiResponse(responseCode = "404", description = "You are not a member of this group."),
            @ApiResponse(responseCode = "409", description = "The group already has a FORMING or ACTIVE round, or is archived.")
    })
    @PostMapping("/groups/{groupId}/rounds")
    public ResponseEntity<RoundSummary> createRound(@PathVariable UUID groupId, @Valid @RequestBody CreateRoundRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(roundService.createRound(currentUserId(), groupId, request));
    }

    @Operation(summary = "List a group's rounds")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Every round the group has run, in any state."),
            @ApiResponse(responseCode = "404", description = "You are not a member of this group.")
    })
    @GetMapping("/groups/{groupId}/rounds")
    public ResponseEntity<List<RoundSummary>> listRoundsForGroup(@PathVariable UUID groupId) {
        return ResponseEntity.ok(roundService.listRoundsForGroup(currentUserId(), groupId));
    }

    @Operation(summary = "Get a round with its participants and cycles")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The round, its participants and, once active, its full cycle schedule."),
            @ApiResponse(responseCode = "404", description = "The round does not exist, or you are not a member of its group.")
    })
    @GetMapping("/rounds/{roundId}")
    public ResponseEntity<RoundDetail> getRound(@PathVariable UUID roundId) {
        return ResponseEntity.ok(roundService.getRound(currentUserId(), roundId));
    }

    @Operation(summary = "Update a forming round's terms")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Round updated."),
            @ApiResponse(responseCode = "400", description = "Validation failed."),
            @ApiResponse(responseCode = "403", description = "You are not an admin of this group."),
            @ApiResponse(responseCode = "404", description = "The round does not exist, or you are not a member of its group."),
            @ApiResponse(responseCode = "409", description = "The round is no longer FORMING — the participant list and terms froze at activation.")
    })
    @PatchMapping("/rounds/{roundId}")
    public ResponseEntity<RoundSummary> updateRound(@PathVariable UUID roundId, @Valid @RequestBody UpdateRoundRequest request) {
        return ResponseEntity.ok(roundService.updateRound(currentUserId(), roundId, request));
    }

    @Operation(summary = "Add a group member to a forming round")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Participant added."),
            @ApiResponse(responseCode = "400", description = "Validation failed, or that user is not a member of this group."),
            @ApiResponse(responseCode = "403", description = "You are not an admin of this group."),
            @ApiResponse(responseCode = "404", description = "The round does not exist, or you are not a member of its group."),
            @ApiResponse(responseCode = "409", description = "That user is already a participant, or the round is no longer FORMING.")
    })
    @PostMapping("/rounds/{roundId}/participants")
    public ResponseEntity<ParticipantSummary> addParticipant(@PathVariable UUID roundId, @Valid @RequestBody AddParticipantRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(roundService.addParticipant(currentUserId(), roundId, request));
    }

    @Operation(summary = "Remove a participant from a forming round")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Participant removed."),
            @ApiResponse(responseCode = "403", description = "You are not an admin of this group."),
            @ApiResponse(responseCode = "404", description = "The round does not exist, you are not a member of its group, or that user is not a participant."),
            @ApiResponse(responseCode = "409", description = "The round is no longer FORMING — the participant list and terms froze at activation.")
    })
    @DeleteMapping("/rounds/{roundId}/participants/{userId}")
    public ResponseEntity<Void> removeParticipant(@PathVariable UUID roundId, @PathVariable UUID userId) {
        roundService.removeParticipant(currentUserId(), roundId, userId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Join a forming round yourself")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "You are now a participant."),
            @ApiResponse(responseCode = "404", description = "The round does not exist, or you are not a member of its group."),
            @ApiResponse(responseCode = "409", description = "You are already a participant, or the round is no longer FORMING.")
    })
    @PostMapping("/rounds/{roundId}/join")
    public ResponseEntity<ParticipantSummary> joinRound(@PathVariable UUID roundId) {
        return ResponseEntity.ok(roundService.joinRound(currentUserId(), roundId));
    }

    @Operation(summary = "Leave a forming round",
            description = "Only while FORMING. Leaving an ACTIVE round is an exit — see Exits & Settlement.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "You are no longer a participant."),
            @ApiResponse(responseCode = "404", description = "The round does not exist, or you are not a participant in it."),
            @ApiResponse(responseCode = "409", description = "The round is no longer FORMING — the participant list and terms froze at activation.")
    })
    @PostMapping("/rounds/{roundId}/leave")
    public ResponseEntity<Void> leaveRound(@PathVariable UUID roundId) {
        roundService.leaveRound(currentUserId(), roundId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Cancel a forming round")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Round cancelled."),
            @ApiResponse(responseCode = "403", description = "You are not an admin of this group."),
            @ApiResponse(responseCode = "404", description = "The round does not exist, or you are not a member of its group."),
            @ApiResponse(responseCode = "409", description = "The round is no longer FORMING and cannot be cancelled.")
    })
    @PostMapping("/rounds/{roundId}/cancel")
    public ResponseEntity<RoundSummary> cancelRound(@PathVariable UUID roundId) {
        return ResponseEntity.ok(roundService.cancelRound(currentUserId(), roundId));
    }

    @Operation(summary = "Activate a round",
            description = "Freezes the participant list, assigns payout positions (members who have completed a round "
                    + "in this group first, each tier shuffled) and generates every cycle up front, so the whole "
                    + "schedule is visible from day one. No money moves.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Round is ACTIVE; the response includes the full cycle schedule."),
            @ApiResponse(responseCode = "403", description = "You are not an admin of this group."),
            @ApiResponse(responseCode = "404", description = "The round does not exist, or you are not a member of its group."),
            @ApiResponse(responseCode = "409", description = "The round is not FORMING, has fewer than 2 participants, or has no first payout date.")
    })
    @PostMapping("/rounds/{roundId}/activate")
    public ResponseEntity<RoundDetail> activate(@PathVariable UUID roundId) {
        return ResponseEntity.ok(roundService.activate(currentUserId(), roundId));
    }

    private UUID currentUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
