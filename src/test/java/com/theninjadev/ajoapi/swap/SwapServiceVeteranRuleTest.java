package com.theninjadev.ajoapi.swap;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SwapServiceVeteranRuleTest {

    private final SwapService swapService =
            new SwapService(null, null, null, null, null, null, null, null, null);

    @Test
    void bothNewcomersAreAlwaysAllowed() {
        assertThat(swapService.violatesVeteranPrecedence(false, false, 1, 4)).isFalse();
        assertThat(swapService.violatesVeteranPrecedence(false, false, 4, 1)).isFalse();
    }

    @Test
    void bothVeteransAreAlwaysAllowed() {
        assertThat(swapService.violatesVeteranPrecedence(true, true, 1, 4)).isFalse();
        assertThat(swapService.violatesVeteranPrecedence(true, true, 4, 1)).isFalse();
    }

    @Test
    void newcomerBehindVeteranIsRejectedRegardlessOfWhoIsCaller() {
        // Veteran at position 1, newcomer at position 4 — swapping would move the newcomer ahead.
        assertThat(swapService.violatesVeteranPrecedence(true, false, 1, 4)).isTrue();   // caller = veteran
        assertThat(swapService.violatesVeteranPrecedence(false, true, 4, 1)).isTrue();   // caller = newcomer
    }

    @Test
    void veteranBehindNewcomerIsAllowedRegardlessOfWhoIsCaller() {
        // Newcomer at position 1, veteran at position 4 — swapping moves the veteran ahead.
        assertThat(swapService.violatesVeteranPrecedence(true, false, 4, 1)).isFalse();  // caller = veteran
        assertThat(swapService.violatesVeteranPrecedence(false, true, 1, 4)).isFalse();  // caller = newcomer
    }
}
