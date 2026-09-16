package com.theninjadev.ajoapi.config;

import com.theninjadev.ajoapi.auth.DuplicateEmailException;
import com.theninjadev.ajoapi.auth.DuplicatePhoneException;
import com.theninjadev.ajoapi.auth.InvalidCredentialsException;
import com.theninjadev.ajoapi.auth.InvalidPhoneNumberException;
import com.theninjadev.ajoapi.auth.InvalidRefreshTokenException;
import com.theninjadev.ajoapi.group.AlreadyGroupMemberException;
import com.theninjadev.ajoapi.group.CannotRemoveLastAdminException;
import com.theninjadev.ajoapi.group.CannotRemoveSelfException;
import com.theninjadev.ajoapi.group.DuplicatePendingInviteException;
import com.theninjadev.ajoapi.group.GroupNotFoundException;
import com.theninjadev.ajoapi.group.InsufficientRoleException;
import com.theninjadev.ajoapi.group.InviteNotFoundException;
import com.theninjadev.ajoapi.group.InviteNotPendingException;
import com.theninjadev.ajoapi.group.NotGroupMemberException;
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

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidationFailure(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    }
}
