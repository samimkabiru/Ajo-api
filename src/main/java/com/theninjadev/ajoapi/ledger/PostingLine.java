package com.theninjadev.ajoapi.ledger;

import java.util.UUID;

public record PostingLine(UUID accountId, long amountKobo) {
}
