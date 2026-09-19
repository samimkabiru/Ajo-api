package com.theninjadev.ajoapi.round;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record AddParticipantRequest(
        @NotNull UUID userId
) {}
