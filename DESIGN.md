# DESIGN.md — why Ajo is built this way

`CLAUDE.md` says *what* the rules are. This file says *why* they exist, and
what each one is protecting against. It is written for someone who can read
the code but wants to know the reasoning — including future you.

Read it in order the first time. After that, treat it as reference.

---

## 1. What the app is, and the one sentence everything serves

A rotating savings circle. A group agrees a monthly amount and a payout
order. Everyone contributes each month, one member collects the whole pot,
and the rotation continues until every member has collected exactly once.
Fifteen members means fifteen months.

Everything in this codebase exists to keep one sentence true:

> At the close of a round, every participant's contributed total equals
> their collected total.

Ajo has no interest, no profit, no fees. It does not move money *between*
people — it moves money through *time*. The member who collects in month 1
receives an interest-free loan from the group; the member who collects in
month 15 has been lending all year. In naira terms nobody gains or loses.

That sentence is the test you apply to any new rule. If a change would break
it, the change is wrong, or the sentence needs a stated exception (there is
exactly one — see §10).

---

## 2. Money is integers, and nothing else

Every amount in this system is a `long` holding **kobo**. ₦10,000 is stored
as `1000000`.

**Why not decimals?** Computers cannot represent most decimal fractions
exactly in binary floating point. In Java, `0.1 + 0.2` is
`0.30000000000000004`. That error is irrelevant for a progress bar and fatal
here: the ledger's core rule is that a set of entries sums to **exactly**
zero, and floating point cannot promise that. Sum a few thousand entries and
you get `0.0000000001`, and a valid posting is rejected.

Integers have no such problem. `100 + 200` is exactly `300`, always.

**Why not `BigDecimal`?** It is exact, so it would work — but it invites
scale and rounding-mode questions at every operation, and it is slower and
heavier. Minor units sidestep the whole category. This is what most payment
systems do.

The conversion to naira happens at the very edge — in the UI, when showing a
number to a human. Inside the backend, money is always kobo.

---

## 3. The ledger

### What double-entry actually means

A **ledger account** is a named bucket. A **ledger entry** is a signed
movement into or out of one account. Entries are written in groups sharing a
`transaction_id`, and **every group must sum to exactly zero**.

That is the whole idea. Money never appears or vanishes; it only moves from
somewhere to somewhere.

A ₦10,000 contribution writes two entries:

```
+1000000  PLATFORM_CASH     the platform now physically holds this
-1000000  ROUND_POOL        the pool now owes this out
```

Sums to zero. Cash went up, and the obligation to pay it out went up by the
same amount. Liabilities are negative because they represent money owed.

### Why balances are never stored

There is no `balance` column anywhere in this system, and adding one would
be a defect. A balance is always `SUM(amount_kobo)` over an account's
entries.

A stored balance is a second source of truth. The moment one exists, some
code path updates the entries and forgets the balance, or updates the
balance and forgets the entries, and now the system disagrees with itself
about how much money there is. Deriving it makes that impossible by
construction.

The cost is a `SUM` query instead of a column read. That is a real cost at
scale, and the standard answer is a periodically materialised snapshot plus
entries since — but the snapshot is still derived, never authoritative. Do
not shortcut this for performance before there is a measured problem.

### The posting primitive

`LedgerService.post(type, referenceId, lines)` is the only thing in this
codebase that writes `ledger_entries`. It:

1. rejects fewer than two lines
2. rejects any zero-amount line
3. rejects the set unless it sums to exactly zero
4. verifies every account exists
5. writes all entries under one generated `transaction_id`

Because it is the only writer, "the books balance" is not a property anyone
has to maintain. It is enforced at the one door.

`referenceId` points back at the domain row that caused the movement — a
contribution, a payout, a refund. It is deliberately not a foreign key,
because the target table varies by entry type. The ledger does not know what
a contribution is, and should not.

### Append-only

Entries are never updated or deleted. To reverse something you post an equal
and opposite entry. That is why there is no `updated_at` and no soft-delete
on the ledger tables.

An audit trail you can edit is not an audit trail.

---

## 4. Letting the database enforce the domain

Several of this system's most important rules are not in Java at all.

**One cycle per participant, one beneficiary per cycle.** A round with N
participants has exactly N cycles, and each participant is the beneficiary
of exactly one. That is enforced by unique constraints on
`(round_id, payout_position)` and `(round_id, beneficiary_id)`. Service code
could check it, but service code has bugs and races; a constraint does not.

**Partial unique indexes.** Several rules apply only to rows in a particular
state:

- one *pending* invite per phone per group
- one *non-completed* round per group
- one *pending* outgoing swap request per participant
- one *open* exit per participant

Each of these is a unique index with a `WHERE` clause. A full unique
constraint would be wrong: it would stop someone being re-invited after
leaving, or stop a second round after the first completed. This pattern
appears throughout the schema and is always deliberate — a partial index
that looks incomplete almost certainly is not.

**Check constraints.** `amount_kobo <> 0` on ledger entries,
`actual <= expected` on payouts, `settled_amount <= amount` on claims. These
are cheap, and they mean a bug in the service layer cannot write a nonsense
row — it fails loudly at the database instead of quietly corrupting data.

**Flyway owns the schema; Hibernate only validates it.** `ddl-auto: validate`
means Hibernate checks that entities match the real tables and refuses to
start if they do not. It never modifies anything. Two systems competing to
define the schema is how a production database ends up not matching its
migrations.

And: **an applied migration is never edited.** Flyway checksums them.
Schema mistakes are fixed by adding a new migration forward. There are
fifteen of them for this reason.

---

## 5. Idempotency

### The problem

A client sends "pay ₦10,000". The request succeeds, but the response is lost
to a flaky connection. The client retries. Without protection, the member has
now paid ₦20,000.

This is not an edge case. It is the normal behaviour of networks.

### The solution, in two layers

The client generates a UUID per payment attempt and sends it as an
`Idempotency-Key` header. The server stores it against the resulting row,
with a unique constraint.

**Layer 1 — the early check.** Before doing any work, look up the key. If a
row exists, return its result and stop. This handles the ordinary retry: the
first request committed seconds ago, and the second returns the same answer
without touching the ledger.

**Layer 2 — the unique constraint.** Two requests can arrive *simultaneously*
with the same key. Both check, both find nothing (the first has not committed
yet), both proceed. The check cannot catch this, because at the moment each
one looks, there genuinely is nothing there.

So the insert is wrapped in a try/catch. The database's unique constraint is
the arbiter — one insert wins, the other gets a
`DataIntegrityViolationException` and is rejected with a 409.

`saveAndFlush`, not `save`: the constraint must fire *now*, not at commit,
or the catch never runs.

### Why the loser must roll back rather than return the original

By the time the constraint fires, the losing request has already posted to
the ledger. If it caught the exception and returned the first request's
result, the transaction would **commit** — leaving ledger entries with no
contribution attached to them. Money from nowhere.

So it throws, the whole transaction unwinds, and the caller gets a 409. Less
polished than returning the original result, but correct. The race it covers
is rare by definition.

### Ordering matters

The idempotency check sits **before** state guards like "the round must be
active". This is not cosmetic. The final payout of a round flips the round to
COMPLETED. If the client retries that payout, a round-status check placed
first would reject it — so the single payout most likely to be retried is the
one where idempotency would break. The check goes first.

A key reused against a *different* resource is a 409, not a silent return.
Returning an unrelated row because the key matched would be worse than
failing.

---

## 6. Transactions

Every money-moving method is `@Transactional`. This is load-bearing, not
decoration.

Without it, each `save()` commits independently: the balance check passes,
three entries insert, the fourth fails, and the ledger is permanently
unbalanced with no way to recover through the API.

Two consequences worth knowing:

**Spring rolls back on unchecked exceptions only.** Every domain exception in
this project extends `RuntimeException`. A checked exception would let the
transaction commit anyway.

**Rejection tests assert that nothing was written.** Throwing is easy;
rolling back is the actual requirement. Several tests count rows before and
after specifically to prove the rollback happened.

There is exactly one deliberate exception:
`@Transactional(noRollbackFor = SwapRequestStaleException.class)`. A stale
swap request marks itself superseded and then throws. Without
`noRollbackFor`, the supersession would roll back too, and the dead request
would sit PENDING forever, failing identically for whoever touched it next.
This is safe *only* because nothing else has been written at that point.

---

## 7. Concurrency

Two users acting at the same instant is the hardest class of bug in this
system, because it passes every test that runs one thing at a time.

### The lock protocol

Written down because it only works if every path follows it:

1. **Cycles are locked before swap-request rows.** Always.
2. **Cycles are locked in id order** (`findAllByIdForUpdate` orders by id).
   Two operations acquiring the same two locks in opposite orders deadlock.
3. **A cycle's lock also guards its beneficiary's position.** In an active
   round each participant owns exactly one cycle, so anyone changing a
   position has already locked that participant's cycle.
4. **The group row guards the group's existence and archive state.** Deleting
   a group, creating, activating or deleting a round in it, every write to a
   FORMING round (terms, joining, leaving, adding or removing participants,
   cancelling) and every group write lock the `groups` row first
   (`findByIdForUpdate`). Without that, a delete and an activation could each
   read a state that permits them, and commit an active round into a deleted
   group; or a join could land between activation reading the participant
   list and committing, leaving a participant with no position and no cycle.
   The round, read before the lock, is re-read under it, and a round that
   vanished while waiting is a 404. (Deleting a group reaches into round tables,
   so `group` and `round` depend on each other. That cycle is deliberate: the
   operation spans both, and a coordinator just to hide it would cost more.)

`SELECT ... FOR UPDATE` — a pessimistic write lock — makes the second
transaction *wait* rather than proceed on stale data.

The rule that makes this work: **once any code path locks a row, every path
that writes that row must take the same lock.** A lock only one participant
respects protects nothing. This is why `payout` locks its cycle even though
only `accept` originally needed to.

### The persistence-context trap

This one is not obvious and cost real debugging.

Within a transaction, Hibernate caches every entity it loads. Load a row
twice and you get the *same object* back, not a fresh read. Normally that is
a feature. Here it is a bug:

- you read the swap request to learn which rows to lock
- you wait for the lock — meanwhile another transaction swaps one of them
  and commits
- you acquire the lock, read the request again, and Hibernate hands you the
  **stale copy from before you waited**

The lock was supposed to give you current data, and the cache threw it away.

So every locking method here has the same two-phase shape:

```
Phase 1 — discovery, no locks. Work out which rows to lock. Keep only ids.
entityManager.clear();
Phase 2 — take the locks, then reload everything fresh.
```

`clear()` only helps if you stop touching what you loaded before it. In
`accept` and `buyIn`, phase 1 is wrapped in a bare `{ }` block so its
variables go out of scope — the compiler enforces what discipline alone
would not.

### Deferred constraints

Swapping two participants' positions temporarily violates the uniqueness
rules that guarantee nobody collects twice. Update the first row and, for one
statement, two cycles share a beneficiary — even though the end state is
perfectly valid.

Postgres checks unique *indexes* per statement, so the swap fails. The fix is
unique *constraints* declared `DEFERRABLE INITIALLY IMMEDIATE`, with
`SET CONSTRAINTS ... DEFERRED` issued inside the swap transaction only.
Postgres then checks them once, at commit, against the finished state.

`INITIALLY IMMEDIATE` is deliberate: everywhere else behaves exactly as
before. Making them deferred by default would move every violation in the
system from the statement that caused it to the commit, which is much harder
to debug.

### Optimistic confirmation

Locks stop two writers colliding. They do not stop a human acting on a stale
screen.

An admin recording a cash payout may have loaded the page before a swap
changed who the beneficiary is. So `PayoutRequest` carries
`expectedBeneficiaryUserId`, checked against the locked cycle. A mismatch is
a 409 telling them to reload. Without it, the admin hands cash to one person
and the system records it against another.

---

## 8. The money flows

### Contribution

```
+amount  PLATFORM_CASH
-amount  ROUND_POOL
```

Cycles open **lazily** — the first contribution to arrive flips a cycle from
SCHEDULED to OPEN, provided the date has come. This was chosen over a
scheduled job for two reasons: it cannot silently fail to run, and it is
testable with a fixed clock instead of a scheduler.

An admin may record a cash contribution for a member. `recorded_by` and
`method` capture who said the money arrived — which is the audit trail that
matters when a cash payment is later disputed.

### Payout, and the cap

```
-amount  PLATFORM_CASH
+amount  ROUND_POOL
```

**The payout is capped by the pool's actual balance.** If nine of ten
members paid, the beneficiary receives nine shares. The platform never pays
out money it did not receive.

This one line is what makes the ledger load-bearing rather than decorative.
The amount is computed server-side and is never accepted from the client.

When the payout is short, a **shortfall claim** records what the beneficiary
is owed. The claim is the group's IOU, and it is settled later from money
that does not exist yet (see §11).

### Time

A `Clock` bean is injected everywhere a timestamp or date is needed, and the
code always calls `Instant.now(clock)` / `LocalDate.now(clock)`, never the
no-arg versions. Rounds run for a year or more; tests substitute a fixed
clock and traverse them in milliseconds. A single bare `Instant.now()`
somewhere makes a whole area untestable.

---

## 9. Exposure

One derived number answers "is this person square with the group":

```
exposure = collected + refunded + claimsSettled − contributed − repaid
```

- **positive** → they owe the group
- **negative** → the group owes them
- **zero** → clean

All five terms matter. Drop `claimsSettled` and a beneficiary who was
underpaid and later made whole reads as owed forever. Drop `refunded` and a
settled leaver never clears.

It is never stored. Always summed, always current.

The value of one formula rather than a set of rules: your mum described two
different exit cases — refund them what they paid, or make them pay back
what they took. Those are the same subtraction with opposite signs. One
calculation, branch on the sign, and the third case (exactly square) is
handled for free rather than forgotten.

**Exposure is not arrears.** See §12 — this distinction caused a real bug.

---

## 10. Exits

A participant leaving an active round.

**Exit is gated on exposure.** Zero completes immediately. Positive means
they owe, and the exit is *blocked* — modelled as a `PENDING_EXIT` state
rather than an error, so there is a record that they tried to leave and how
much they must pay. Partial repayment walks the debt down.

**Refunds are the slow path; debts are due now.** This asymmetry is not
arbitrary. The pool does not hold the leaver's money — every month's
collections were paid straight out — so the refund waits for funds that
actually exist. A debt is already in the defaulter's hand, and every day it
stays open, everyone else carries the risk.

**Cycles are only touched when an exit completes.** While PENDING_EXIT the
participant keeps their position and cycle, so cancelling an exit needs no
repair. This rule shaped the whole slice.

**And it had to bend once.** For a leaver who is *owed* money, the original
rule deadlocked:

- the exit completes when they are refunded
- the refund comes from the vacant pot
- the cycle goes vacant when the exit completes

Nothing could start. So vacating happens **lazily, at settlement time**: an
admin settling a cycle vacates it as the opening act. Right up until that
moment the cycle is intact and cancel is still free.

A test found this, not a review. That is the argument for writing the
end-to-end scenario rather than only the unit cases.

**The round-completion rule follows from all this.** A round completes when
every cycle is PAID, VACANT or SETTLED — not "everyone is whole". Those are
different facts, and conflating them would let one defaulter keep a round
open forever, blocking the group from starting another. Obligations outlive
the round: repayment is permitted after a round completes, because
otherwise the money a defaulter owes becomes uncollectable.

That is the one stated exception to §1's sentence. **The books always
balance** — an unpaid debt sits as an open receivable and the ledger is
fine. **Everyone whole** is only true when every obligation has settled.
Two different assertions, and the tests state them separately.

---

## 11. Buy-ins, and the slot idea

When a leaver is owed money, the clean resolution is a replacement: someone
pays in the leaver's contributed total, the leaver is refunded from it
immediately, and the round continues with its schedule untouched. The pool
nets to zero.

The design decision that makes this work: **a participant row is a slot, not
a person.** A buy-in changes `round_participants.user_id`. Everything keyed
on `participant_id` — position, cycle, contribution history, payout
eligibility — comes with it automatically. That is exactly what the
replacement is paying for.

The alternative (a new participant row for the replacement) would have meant
special-casing the invariant for both parties. With slots it needs no
special-casing at all: the test asserts contributed-equals-collected **per
slot**, and a slot that changed hands mid-round passes like any other.

Two consequences:

- The leaver has no participant row afterwards, so `buy_ins` **snapshots**
  both user ids. After the transfer the participant cannot tell you who left.
- A buy-in does **not** call `completeExit`. That helper marks the
  participant EXITED and vacates the cycle — both wrong here, because the
  participant is now the replacement and keeps the cycle. It instead marks
  the slot ACTIVE again, since it was PENDING_EXIT while the leaver was
  going.

The money is deliberately **two ledger transactions**, not one netted
movement: money in from the replacement, money out to the leaver. A single
netted posting would hide what happened.

---

## 12. Settlement, and the identity behind it

When nobody buys in, the leaver's cycle goes **vacant**: it keeps collecting
contributions from everyone else and pays out to nobody. That unclaimed pot
is what funds the settlement.

### The identity

Work the three-person case. Ada, Bola, Chidi, ₦10,000 a month.

Chidi pays month 1 and leaves. Nobody replaces him.

| | Month 1 | Month 2 | Month 3 |
|---|---|---|---|
| Pot | 30,000 | 20,000 | 20,000 |
| Collects | Ada (30,000) | Bola (20,000, short 10,000) | nobody |

Month 3's ₦20,000 is unclaimed. It settles Chidi's ₦10,000 refund and
Bola's ₦10,000 claim. Exactly.

**This is not a coincidence of these numbers.** The leaver is owed one
contribution for each month they paid; the group is short one contribution
for each remaining payout they will not fund. Those two quantities plus the
vacant pot always reconcile, for any position, any exit month, any group
size.

### How settlement distributes

1. **Refund first** — the leaver's money is why the pot exists
2. **Claims next**, oldest first, partially where the money runs out
3. **Anything left stays in the pool** for a later pass

Claims can be **part-paid**, so `shortfall_claims` carries a settled
*amount*, not a boolean. `shortfall_settlements` records each payment with
the cycle that funded it — which is what makes repeated passes correct: each
pass knows how much of the pot it has already distributed.

**Repeated passes are necessary**, not a nicety. In the fifteen-person case,
claims keep arriving in months 11–15 — *after* the vacant pot arrived in
month 10. So a cycle only reaches SETTLED when nothing is outstanding
round-wide, not when its own pot is exhausted. Until then it stays VACANT and
can be settled again.

---

## 13. Arrears netting

The last piece, and the system's real answer to defaults.

A member who misses a contribution and later collects their own payout has
their **arrears withheld**, and the withheld money settles the claims their
absence caused.

This matters because it changes where the risk sits. A member who misses
payments *before* collecting is barely a risk at all — the group is holding
their payout, and it can simply be reduced. The dangerous case is missing
payments *after* collecting, and that is what exposure and the exit rules
handle. Physical Ajo relies on everyone knowing where you live. This is the
software equivalent, and it is strictly better: it works before the money
leaves.

### Two traps, both found by working examples through

**Arrears are not exposure.** Exposure is *negative* for anyone who has not
yet collected, so it cannot detect a missed contribution. Someone who skipped
month 1 and paid month 2 still has negative exposure when their turn comes.
Arrears are a different quantity entirely: cycles they should have paid into
by now, minus the ones they did.

They are computed as a **set difference** over cycle ids, not by subtracting
a running total — a duplicate or early contribution would distort a total,
but cannot distort a count of missed cycles.

**A beneficiary's own cycle nets itself.** Arrears count only *earlier*
cycles, and a shortfall claim excludes the beneficiary's own missing share.

Why: a member who does not pay into the cycle they are collecting has
already shrunk that pot. Counting it in arrears as well would take it twice —
and raising a claim for it would have the group owing a defaulter the money
the defaulter failed to pay. In the extreme case (pay nothing, collect in
cycle 3) the naive version leaves an open claim in the defaulter's favour
that a later settlement would actually pay them.

The withheld money also never reaches the beneficiary's own claims, for the
same reason: the fall-through would otherwise find the claim just raised on
their cycle and hand the money straight back.

**A payout can be zero.** When arrears consume the whole pot, the
beneficiary collects nothing — a legitimate outcome, not an error. The cycle
is still PAID, and `ledger_transaction_id` is nullable because a fully
withheld payout posts nothing of its own.

---

## 14. How this is tested, and why that way

**Real Postgres, via Testcontainers.** Not H2. This system depends on
Postgres-specific behaviour — partial indexes, deferrable constraints, row
locking semantics. H2 would lie about all three.

**Test classes are not `@Transactional`.** If the framework rolled back each
test, a rollback test could not distinguish the service rolling back from the
framework doing it. Tests commit for real, which is also why phone numbers
come from a shared counter — nothing is cleaned up between tests.

**Rejection tests assert that nothing was written**, not merely that an
exception was thrown.

**Invariant tests, not just unit tests.** The most valuable tests here run a
whole round and assert the sentence from §1. There is one for the clean case,
one with a buy-in, one with a vacant cycle and settlement, and one with a
defaulter. Each found at least one bug that unit tests had missed.

**One genuine concurrency test.** Two conflicting swap accepts fire on
separate threads behind a latch. Exactly one succeeds, the other is
superseded, and the round's structure survives. Without the lock this
corrupts positions or fails at commit — it is the only test that proves the
locking does anything.

**Unreachable branches are documented, not tested.** Several guards cannot be
triggered through the API today. The pattern is to keep the guard and leave a
comment saying why it is unreachable, rather than contriving database state
to drive a test that proves nothing.

---

## 15. Known limits

Honest edges, worth being able to state:

- **Payouts draw on the whole round pool, not a per-cycle pot.** Leftover
  withheld money or a vacant pot can top up a later beneficiary's short pot —
  occasionally one who skipped their own cycle. Changing this means keeping
  each cycle's money separate.
- **No automatic recovery from a post-collection default** beyond blocking
  their exit and recording the debt. Guarantors are the obvious next step and
  are not built.
- **Settlement is triggered manually by an admin**, not by a scheduled job.
  Deliberate: no scheduler to fail silently, and it keeps the system testable
  with a fixed clock.
- **Single currency.** The `currency` column exists; nothing uses it.
- **No bidding for early positions**, which exists in some real Ajo variants.
- **Email is collected but unused.** Registration accepts an optional email,
  normalises it and enforces uniqueness, and `UserSummary` returns it. Nothing
  reads it: login and password reset are phone-only, nothing is ever emailed,
  and `emailVerified` is always `false`, because no flow sets it. The duplicate
  check's 409 also lets anyone test whether an email is registered. Either give
  email a job (with its own verification and non-disclosure), or drop the
  columns and the field.
- **`login_attempt_counters` only grows under attack.** A row goes away on a
  successful login or password reset, so for legitimate use the table stays
  small. A number that only ever fails keeps its row for good, so an attacker
  spraying a million numbers leaves a million rows. If that ever matters, the
  fix is a periodic delete of rows whose window and block have both expired.
  None is built: it would be the codebase's first scheduled job, and it would
  not run reliably on a host that spins down.
- **Archiving can hide a debt from its creditor's default view.** `GET /groups`
  leaves out archived groups, so when an admin archives a group that still
  owes a member money, it drops out of that member's list. The obligation
  survives archiving, and repayment and settlement stay open, but it is only
  reachable under `?archived=true` and nothing surfaces it. If that ever
  matters, the fix is for the default list to also include archived groups
  where the caller has outstanding exposure. Not built.

---

## 16. If you only remember five things

1. **Balances are derived, never stored.** One source of truth.
2. **Money moves only through `LedgerService.post`, in sets that sum to
   zero.** The books cannot go wrong at any other door.
3. **The database enforces the domain's structural rules**, not just the
   service layer — because constraints do not have bugs or races.
4. **Idempotency needs two layers**: a check for retries, a unique constraint
   for races, and a rollback for the loser.
5. **After a lock, re-read everything.** A lock that hands you cached data
   protects nothing.
