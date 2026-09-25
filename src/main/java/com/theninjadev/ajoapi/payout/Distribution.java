package com.theninjadev.ajoapi.payout;

import java.util.List;

/** The outcome of one distribution pass: what is left, and which claims received money. */
public record Distribution(long remaining, List<ShortfallClaim> settled) {}
