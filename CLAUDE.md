# CLAUDE.md

Guidance for Claude Code when working in this repository.

## What this is

Ajo API — a Spring Boot backend for rotating savings circles (Nigerian
*Ajo* / *Esusu*, also called *Adashe*).

A group of people agree on a fixed monthly amount and a payout order.
Everyone contributes each month. One member collects the entire pot that
month. The rotation continues until every member has collected exactly
once. Fifteen members means fifteen months.

This is the digital version of something people already do with cash.

## The invariant

Everything in this codebase exists to keep one sentence true:

> **At the close of any round, every participant's contributed total
> equals their collected total.**

Ajo has no interest, no profit, no loss. It moves money through *time*,
not between people. The member who collects in month 1 receives an
interest-free loan from the group; the member who collects in month 15
has been lending the whole time. In naira terms nobody gains or loses.

Every rule about exits, refunds, vacant cycles and shortfall claims is
bookkeeping in service of that sentence.

Two distinct assertions follow from it, and they are not the same thing:

- **Books balance** — always true, in every scenario, no exceptions.
  Guaranteed by double-entry. An unpaid debt sits as an open receivable;
  the ledger still balances.
- **Everyone whole** — true only when all obligations have actually
  settled. A round that closes with a defaulter leaves this false, and
  that is not a bug in the code.

## Hard rules

These are not style preferences. Violating any of them is a defect.

1. **Money is stored as integer kobo.** `long` or `BigInteger`. Never
   `double`, never `float`, never `BigDecimal` in the ledger. ₦10,000 is
   `1000000`.

2. **Never edit an applied Flyway migration.** Flyway checksums migration
   files; editing one breaks startup. Schema mistakes are fixed by adding
   a new migration forward, never by rewriting history.

3. **The ledger is append-only.** No `UPDATE`, no `DELETE`, no
   `updated_at`, no soft-delete column on `ledger_entries` or
   `ledger_accounts`. To reverse a posting, post an equal and opposite
   one.

4. **Balances are always derived, never stored.** A balance is
   `SUM(amount_kobo)` over that account's entries. Do not add a `balance`
   column to any table, ever, for any reason — including performance.

5. **All money movement goes through `LedgerService.post()`.** Nothing
   else writes to `ledger_entries`. That method validates that a posting
   sums to zero and rejects it otherwise.

6. **Payouts are capped by the pool's actual balance.** The platform
   never pays out money it did not receive. If 9 of 10 members paid, the
   beneficiary receives 9 shares and a shortfall is recorded.

7. **Do not modify `LedgerService` or its tests** unless explicitly asked.
   This is the core of the project and is maintained by hand.

## Architecture

Feature-packaged, not layer-packaged. One package per domain area, each
containing its own entities, repositories, service, DTOs and mappers.

```
com.theninjadev.ajoapi
├── ledger/      ledger accounts + entries, LedgerService (the primitive)
├── config/      Spring configuration (ClockConfig, SecurityConfig)
└── ...          further feature packages as slices land
```

Test support lives in `com.theninjadev.ajoapi.testsupport`.

### The ledger

Two tables. `ledger_accounts` are named buckets; `ledger_entries` are
signed movements grouped by `transaction_id`. All entries sharing a
transaction id must sum to exactly zero — that is the unit of atomicity.

Account types:

| Type            | One per                | Holds                                  |
|-----------------|------------------------|----------------------------------------|
| `PLATFORM_CASH` | the system (singleton) | all money the platform physically holds |
| `ROUND_POOL`    | round                  | what that round's pool owes out         |
| `PARTICIPANT`   | participant per round  | that person's position with the group   |

A ₦10,000 contribution posts `+1000000` to `PLATFORM_CASH` and
`-1000000` to that round's `ROUND_POOL`. Liabilities are negative
because they represent money owed.

`LedgerEntry.accountId` is a **plain UUID, not a `@ManyToOne`**. This is
deliberate. The ledger is an aggregate query table — it gets summed, not
navigated. A relation invites `entry.getAccount().getBalance()`, which is
the road to a stored balance. Keep the ledger dumb.

`reference_id` points at the domain row that caused the movement (a
contribution, a payout, a shortfall claim). It is intentionally not a
foreign key, since the target table varies by `entry_type`.

### Time

A `Clock` bean is injected wherever timestamps are needed. Always use
`Instant.now(clock)`, never `Instant.now()`. Rounds run for a year or
more; tests substitute a fixed clock to fast-forward through them in
milliseconds. Bare `Instant.now()` makes that impossible.

## Domain model

- **Group** — the people. Persistent across rounds.
- **Round** — one full rotation with its own terms (amount, interval,
  start date). New terms means a *new round*, not a mutated one.
  States: `FORMING` → `ACTIVE` → `COMPLETED`.
- **RoundParticipant** — a user in a round, with a `payout_position`.
- **Cycle** — one month within a round. Has exactly one beneficiary,
  or none if that participant exited before collecting (`VACANT`).
- **Contribution** — one member's payment into one cycle.
- **Payout** — a beneficiary collecting a cycle's pot.
- **ShortfallClaim** — what a beneficiary is owed when their pot came up
  short because someone stopped contributing.

### Structural rules

- A round has exactly as many cycles as participants, and each
  participant is the beneficiary of exactly one cycle. Enforce with
  database unique indexes on `(round_id, payout_position)` and
  `(round_id, beneficiary_id)`, not just service-layer checks.
- Cycles are generated up front when a round activates, so the full
  schedule is visible to everyone on day one.
- **Participant list freezes at activation.** You cannot add a sixteenth
  member in month four — that would change the cycle count and shift
  everyone's position. New members wait for the next round.
- Monthly cadence, payout on a fixed calendar date at month end. A round
  activating mid-month starts its first cycle the *next* full month.

### Exits

One formula, not two rules:

```
exposure = collected − contributed
```

- `exposure < 0` → the group owes them. Exit permitted, refund scheduled.
- `exposure > 0` → they owe the group. Exit **blocked** until it is zero.
- `exposure = 0` → clean. Exit immediately.

Both totals are derived from ledger entries, never from stored counters.

A blocked exit is a `PENDING_EXIT` **state**, not an error. The member
stays liable for ongoing contributions, the amount owed is shown to them
and to the group, and partial repayment is accepted — exposure walks down
toward zero.

Refunds are the **slow path**: settled at the leaver's original cycle, so
no current beneficiary is shorted. Immediate only if a replacement buys
in at the leaver's contributed total and takes over their position. Debts
are due immediately, because that money is already in the defaulter's
hand and every remaining member is carrying the risk until it is repaid.

### Position swaps

A member needing cash early may ask a later-positioned member to trade
slots. Both positions must be in the future (a cycle that already paid
out cannot be traded), both parties must consent, and the swap is applied
atomically. Payment obligations never change — only collection timing.

When a member requests to leave, offer the swap first. It keeps them in
the group and disturbs nobody.

## Build and run

Maven wrapper. Windows: `mvnw.cmd`.

```
mvnw.cmd clean compile        # build
mvnw.cmd spring-boot:run      # run locally (reads .env via springboot4-dotenv)
mvnw.cmd test                 # all tests
mvnw.cmd test -Dtest=ClassName
```

Spring Boot 4.1.1, Java 26, PostgreSQL, Flyway, Lombok, MapStruct.

Local Postgres on `localhost:5432/ajo`. Credentials come from `.env`
(gitignored) — `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`. Never hardcode
credentials in `application.yml` or `pom.xml`.

`ddl-auto: validate`. Flyway owns the schema; Hibernate only verifies it.
Never set this to `update` or `create`.

### Testing

Integration tests extend `testsupport.AbstractIntegrationTest`, which
starts a real Postgres via Testcontainers (`postgres:16-alpine`) with
`@ServiceConnection`. Docker must be running.

**Testcontainers 2.x** — modules are named `testcontainers-postgresql`
and `testcontainers-junit-jupiter`; container classes live under
`org.testcontainers.postgresql`. The 1.x names and packages do not exist.
JUnit 4 support was removed in 2.0.

Do not annotate test classes with `@Transactional`. Tests must commit for
real, otherwise a rollback test cannot distinguish the service rolling
back from the test framework rolling back.

Money-path tests assert the invariant, not just the absence of
exceptions. A rejection test asserts that nothing was persisted, not
merely that an exception was thrown.

## Slice order

1. ~~Ledger schema + `LedgerService.post()` + tests~~ — done
2. Auth (JWT + refresh rotation, ported from TaskFlow)
3. Groups and membership
4. Rounds, participants, cycle generation
5. Contributions (idempotency keys land here)
6. Payouts (balance cap, first invariant test end-to-end)
7. Position swaps
8. Exits and settlement (hardest, deliberately last)
9. Paystack integration (webhook signature verification, reconciliation)
10. Deploy

Later slices depend on earlier ones. Do not build ahead of the current
slice.

## Out of scope

Deliberately excluded — do not add these:

- Real-time / WebSocket layer
- Multi-currency (NGN only; the `currency` column exists for future use)
- Interest, fees, or any profit mechanism
- Guarantors (a known future direction, not yet designed)
- Bidding for early positions (exists in some real Ajo variants; not modelled)

## Working style

- Propose before writing. Prefer plan mode for anything touching more
  than one file.
- Do not create services, controllers or DTOs unless the task asks for
  them.
- Do not modify `pom.xml` without being asked. The build works.
- When a schema change is needed, add a new migration. Never edit an
  existing one.
