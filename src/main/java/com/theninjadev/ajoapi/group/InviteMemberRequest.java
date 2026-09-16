package com.theninjadev.ajoapi.group;

import jakarta.validation.constraints.NotBlank;

public record InviteMemberRequest(
        @NotBlank String phone
) {}
