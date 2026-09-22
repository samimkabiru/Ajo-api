package com.theninjadev.ajoapi.config;

import com.theninjadev.ajoapi.auth.DuplicateEmailException;
import com.theninjadev.ajoapi.auth.DuplicatePhoneException;
import com.theninjadev.ajoapi.auth.InvalidCredentialsException;
import com.theninjadev.ajoapi.auth.InvalidPhoneNumberException;
import com.theninjadev.ajoapi.auth.InvalidRefreshTokenException;
import com.theninjadev.ajoapi.contribution.*;
import com.theninjadev.ajoapi.group.AlreadyGroupMemberException;
import com.theninjadev.ajoapi.group.CannotRemoveLastAdminException;
import com.theninjadev.ajoapi.group.CannotRemoveSelfException;
import com.theninjadev.ajoapi.group.DuplicatePendingInviteException;
import com.theninjadev.ajoapi.group.GroupNotFoundException;
import com.theninjadev.ajoapi.group.InsufficientRoleException;
import com.theninjadev.ajoapi.group.InviteNotFoundException;
import com.theninjadev.ajoapi.group.InviteNotPendingException;
import com.theninjadev.ajoapi.group.NotGroupMemberException;
import com.theninjadev.ajoapi.ledger.IdempotencyKeyReusedException;
import com.theninjadev.ajoapi.payout.*;
import com.theninjadev.ajoapi.round.*;
import com.theninjadev.ajoapi.swap.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(DuplicatePhoneException.class)
    public ProblemDetail handleDuplicatePhone(DuplicatePhoneException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(DuplicateEmailException.class)
    public ProblemDetail handleDuplicateEmail(DuplicateEmailException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(InvalidPhoneNumberException.class)
    public ProblemDetail handleInvalidPhoneNumber(InvalidPhoneNumberException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ProblemDetail handleInvalidCredentials(InvalidCredentialsException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ProblemDetail handleInvalidRefreshToken(InvalidRefreshTokenException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
    }

    @ExceptionHandler(GroupNotFoundException.class)
    public ProblemDetail handleGroupNotFound(GroupNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(NotGroupMemberException.class)
    public ProblemDetail handleNotGroupMember(NotGroupMemberException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(InsufficientRoleException.class)
    public ProblemDetail handleInsufficientRole(InsufficientRoleException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler(InviteNotFoundException.class)
    public ProblemDetail handleInviteNotFound(InviteNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(InviteNotPendingException.class)
    public ProblemDetail handleInviteNotPending(InviteNotPendingException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(AlreadyGroupMemberException.class)
    public ProblemDetail handleAlreadyGroupMember(AlreadyGroupMemberException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(DuplicatePendingInviteException.class)
    public ProblemDetail handleDuplicatePendingInvite(DuplicatePendingInviteException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(CannotRemoveLastAdminException.class)
    public ProblemDetail handleCannotRemoveLastAdmin(CannotRemoveLastAdminException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(CannotRemoveSelfException.class)
    public ProblemDetail handleCannotRemoveSelf(CannotRemoveSelfException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(RoundNotFoundException.class)
    public ProblemDetail handleRoundNotFound(RoundNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(RoundNotFormingException.class)
    public ProblemDetail handleRoundNotForming(RoundNotFormingException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(GroupHasActiveRoundException.class)
    public ProblemDetail handleGroupHasActiveRound(GroupHasActiveRoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(AlreadyRoundParticipantException.class)
    public ProblemDetail handleAlreadyRoundParticipant(AlreadyRoundParticipantException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(RoundParticipantNotFoundException.class)
    public ProblemDetail handleRoundParticipantNotFound(RoundParticipantNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(InsufficientParticipantsException.class)
    public ProblemDetail handleInsufficientParticipants(InsufficientParticipantsException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(UserNotGroupMemberException.class)
    public ProblemDetail handleUserNotGroupMember(UserNotGroupMemberException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(RoundNotReadyException.class)
    public ProblemDetail handleRoundNotReady(RoundNotReadyException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(CycleNotFoundException.class)
    public ProblemDetail handleCycleNotFound(CycleNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(RoundNotActiveException.class)
    public ProblemDetail handleRoundNotActive(RoundNotActiveException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(CycleNotOpenException.class)
    public ProblemDetail handleCycleNotOpen(CycleNotOpenException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(CycleAlreadyPaidException.class)
    public ProblemDetail handleCycleAlreadyPaid(CycleAlreadyPaidException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(ParticipantNotInRoundException.class)
    public ProblemDetail handleParticipantNotInRound(ParticipantNotInRoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(IncorrectContributionAmountException.class)
    public ProblemDetail handleIncorrectContributionAmount(IncorrectContributionAmountException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(DuplicateContributionException.class)
    public ProblemDetail handleDuplicateContribution(DuplicateContributionException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(MissingIdempotencyKeyException.class)
    public ProblemDetail handleMissingIdempotencyKey(MissingIdempotencyKeyException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(PayoutNotFoundException.class)
    public ProblemDetail handlePayoutNotFound(PayoutNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(CycleAlreadyPaidOutException.class)
    public ProblemDetail handleCycleAlreadyPaidOut(CycleAlreadyPaidOutException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(PayoutNotYetDueException.class)
    public ProblemDetail handlePayoutNotYetDue(PayoutNotYetDueException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(CycleHasNoBeneficiaryException.class)
    public ProblemDetail handleCycleHasNoBeneficiary(CycleHasNoBeneficiaryException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(EmptyPoolException.class)
    public ProblemDetail handleEmptyPool(EmptyPoolException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(NotCycleBeneficiaryException.class)
    public ProblemDetail handleNotCycleBeneficiary(NotCycleBeneficiaryException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    public ProblemDetail handleIdempotencyKeyReused(IdempotencyKeyReusedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(SwapRequestNotFoundException.class)
    public ProblemDetail handleSwapRequestNotFound(SwapRequestNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(SwapRequestNotPendingException.class)
    public ProblemDetail handleSwapRequestNotPending(SwapRequestNotPendingException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(NotSwapTargetException.class)
    public ProblemDetail handleNotSwapTarget(NotSwapTargetException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler(NotSwapRequesterException.class)
    public ProblemDetail handleNotSwapRequester(NotSwapRequesterException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler(CannotSwapWithSelfException.class)
    public ProblemDetail handleCannotSwapWithSelf(CannotSwapWithSelfException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(ParticipantNotActiveException.class)
    public ProblemDetail handleParticipantNotActive(ParticipantNotActiveException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(RequesterAlreadyPaidOutException.class)
    public ProblemDetail handleRequesterAlreadyPaidOut(RequesterAlreadyPaidOutException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(TargetAlreadyPaidOutException.class)
    public ProblemDetail handleTargetAlreadyPaidOut(TargetAlreadyPaidOutException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(OutgoingSwapAlreadyPendingException.class)
    public ProblemDetail handleOutgoingSwapAlreadyPending(OutgoingSwapAlreadyPendingException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(NewcomerCannotMoveAheadOfVeteranException.class)
    public ProblemDetail handleNewcomerCannotMoveAheadOfVeteran(NewcomerCannotMoveAheadOfVeteranException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(SwapRequestStaleException.class)
    public ProblemDetail handleSwapRequestStale(SwapRequestStaleException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(BeneficiaryChangedException.class)
    public ProblemDetail handleBeneficiaryChanged(BeneficiaryChangedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidationFailure(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    }
}
