package com.theninjadev.ajoapi.config;

import com.theninjadev.ajoapi.auth.DuplicateEmailException;
import com.theninjadev.ajoapi.auth.DuplicatePhoneException;
import com.theninjadev.ajoapi.auth.InvalidCredentialsException;
import com.theninjadev.ajoapi.auth.InvalidPhoneNumberException;
import com.theninjadev.ajoapi.auth.InvalidRefreshTokenException;
import com.theninjadev.ajoapi.auth.LoginRateLimitedException;
import com.theninjadev.ajoapi.contribution.*;
import com.theninjadev.ajoapi.exit.*;
import com.theninjadev.ajoapi.group.AlreadyGroupMemberException;
import com.theninjadev.ajoapi.group.CannotRemoveLastAdminException;
import com.theninjadev.ajoapi.group.CannotRemoveSelfException;
import com.theninjadev.ajoapi.group.DuplicatePendingInviteException;
import com.theninjadev.ajoapi.group.GroupArchivedException;
import com.theninjadev.ajoapi.group.GroupHasFormingRoundException;
import com.theninjadev.ajoapi.group.GroupHasRoundInProgressException;
import com.theninjadev.ajoapi.group.GroupNotFoundException;
import com.theninjadev.ajoapi.group.InsufficientRoleException;
import com.theninjadev.ajoapi.group.InviteNotFoundException;
import com.theninjadev.ajoapi.group.InviteNotPendingException;
import com.theninjadev.ajoapi.group.NotGroupMemberException;
import com.theninjadev.ajoapi.ledger.IdempotencyKeyReusedException;
import com.theninjadev.ajoapi.payout.*;
import com.theninjadev.ajoapi.round.*;
import com.theninjadev.ajoapi.swap.*;
import com.theninjadev.ajoapi.verification.InvalidVerificationCodeException;
import com.theninjadev.ajoapi.verification.NoActiveVerificationCodeException;
import com.theninjadev.ajoapi.verification.PhoneAlreadyVerifiedException;
import com.theninjadev.ajoapi.verification.PhoneNotVerifiedException;
import com.theninjadev.ajoapi.verification.TooManyVerificationAttemptsException;
import com.theninjadev.ajoapi.verification.TooManyVerificationRequestsException;
import com.theninjadev.ajoapi.verification.VerificationCodeExpiredException;
import com.theninjadev.ajoapi.verification.VerificationResendTooSoonException;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every error leaves the API as an RFC 9457 ProblemDetail. Extending
 * ResponseEntityExceptionHandler covers Spring MVC's own errors (malformed JSON, unknown route,
 * wrong method or media type); the handlers below cover the domain; the catch-all covers the rest.
 * The security layer forwards its 401/403 here too (see SecurityConfig), so they match.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

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

    // Retry-After for HTTP clients; the same seconds in the body because a browser calling
    // cross-origin can read a non-safelisted header only if CORS exposes it. Always the
    // configured block duration, never the time remaining.
    @ExceptionHandler(LoginRateLimitedException.class)
    public ResponseEntity<ProblemDetail> handleLoginRateLimited(LoginRateLimitedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
        problem.setProperty("retryAfterSeconds", e.getRetryAfterSeconds());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()))
                .body(problem);
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

    @ExceptionHandler(GroupArchivedException.class)
    public ProblemDetail handleGroupArchived(GroupArchivedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(GroupHasRoundInProgressException.class)
    public ProblemDetail handleGroupHasRoundInProgress(GroupHasRoundInProgressException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(GroupHasFormingRoundException.class)
    public ProblemDetail handleGroupHasFormingRound(GroupHasFormingRoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
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

    @ExceptionHandler(RoundAlreadyActivatedException.class)
    public ProblemDetail handleRoundAlreadyActivated(RoundAlreadyActivatedException e) {
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

    @ExceptionHandler(ExitRequestNotFoundException.class)
    public ProblemDetail handleExitRequestNotFound(ExitRequestNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ExitAlreadyRequestedException.class)
    public ProblemDetail handleExitAlreadyRequested(ExitAlreadyRequestedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(ExitNotPendingSettlementException.class)
    public ProblemDetail handleExitNotPendingSettlement(ExitNotPendingSettlementException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(NotExitingParticipantException.class)
    public ProblemDetail handleNotExitingParticipant(NotExitingParticipantException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler(NothingToRepayException.class)
    public ProblemDetail handleNothingToRepay(NothingToRepayException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(RepaymentExceedsDebtException.class)
    public ProblemDetail handleRepaymentExceedsDebt(RepaymentExceedsDebtException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(BeneficiaryNotActiveException.class)
    public ProblemDetail handleBeneficiaryNotActive(BeneficiaryNotActiveException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(BuyInNotFoundException.class)
    public ProblemDetail handleBuyInNotFound(BuyInNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ExitAlreadySettledException.class)
    public ProblemDetail handleExitAlreadySettled(ExitAlreadySettledException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(LeaverOwesGroupException.class)
    public ProblemDetail handleLeaverOwesGroup(LeaverOwesGroupException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(NothingToBuyIntoException.class)
    public ProblemDetail handleNothingToBuyInto(NothingToBuyIntoException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(BuyInAmountMismatchException.class)
    public ProblemDetail handleBuyInAmountMismatch(BuyInAmountMismatchException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    // Two exceptions share this name — one per package — so both are fully qualified.
    @ExceptionHandler(com.theninjadev.ajoapi.contribution.CycleAlreadySettledException.class)
    public ProblemDetail handleCycleAlreadySettledForContribution(
            com.theninjadev.ajoapi.contribution.CycleAlreadySettledException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(com.theninjadev.ajoapi.exit.CycleAlreadySettledException.class)
    public ProblemDetail handleCycleAlreadySettledForSettlement(
            com.theninjadev.ajoapi.exit.CycleAlreadySettledException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(CycleNotVacantException.class)
    public ProblemDetail handleCycleNotVacant(CycleNotVacantException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(SettlementNotYetDueException.class)
    public ProblemDetail handleSettlementNotYetDue(SettlementNotYetDueException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(NothingToSettleException.class)
    public ProblemDetail handleNothingToSettle(NothingToSettleException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(RefundNotFoundException.class)
    public ProblemDetail handleRefundNotFound(RefundNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    // ---- Phone verification

    @ExceptionHandler(PhoneAlreadyVerifiedException.class)
    public ProblemDetail handlePhoneAlreadyVerified(PhoneAlreadyVerifiedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(NoActiveVerificationCodeException.class)
    public ProblemDetail handleNoActiveVerificationCode(NoActiveVerificationCodeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(InvalidVerificationCodeException.class)
    public ProblemDetail handleInvalidVerificationCode(InvalidVerificationCodeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(VerificationCodeExpiredException.class)
    public ProblemDetail handleVerificationCodeExpired(VerificationCodeExpiredException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.GONE, e.getMessage());
    }

    @ExceptionHandler(TooManyVerificationAttemptsException.class)
    public ProblemDetail handleTooManyVerificationAttempts(TooManyVerificationAttemptsException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
    }

    @ExceptionHandler(TooManyVerificationRequestsException.class)
    public ProblemDetail handleTooManyVerificationRequests(TooManyVerificationRequestsException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
    }

    @ExceptionHandler(VerificationResendTooSoonException.class)
    public ProblemDetail handleVerificationResendTooSoon(VerificationResendTooSoonException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
    }

    @ExceptionHandler(PhoneNotVerifiedException.class)
    public ProblemDetail handlePhoneNotVerified(PhoneNotVerifiedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
    }

    // ---- Password reset: one outcome for every failure, so it reveals nothing about accounts.

    @ExceptionHandler(com.theninjadev.ajoapi.verification.PasswordResetFailedException.class)
    public ProblemDetail handlePasswordResetFailed(com.theninjadev.ajoapi.verification.PasswordResetFailedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    // ---- Validation: a readable detail plus one entry per field.

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        var fieldErrors = e.getBindingResult().getFieldErrors();
        String detail = fieldErrors.stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        List<Map<String, String>> errors = fieldErrors.stream()
                .map(error -> Map.of(
                        "field", error.getField(),
                        "message", String.valueOf(error.getDefaultMessage())))
                .toList();
        problem.setProperty("errors", errors);

        return handleExceptionInternal(e, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    // ---- Spring Security. Explicit, so the catch-all below can never turn them into 500s.
    // The messages are fixed on purpose: the exceptions' own text is not for clients.

    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail handleAuthentication(AuthenticationException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Missing or invalid access token");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Access denied");
    }

    // ---- Anything else is a bug: log it in full, tell the client nothing about internals.

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
    }
}
