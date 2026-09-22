package com.theninjadev.ajoapi.swap;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreateSwapRequest(@NotNull UUID targetUserId) {}
