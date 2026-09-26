# Ajo API

[![CI](https://github.com/samimkabiru/Ajo-api/actions/workflows/ci.yml/badge.svg)](https://github.com/samimkabiru/Ajo-api/actions/workflows/ci.yml)

A backend for **Ajo** — a digital version of the rotating savings circle used across Nigeria (also known as *esusu* or *adashe*).

**Live API:** https://ajo-api-p1xw.onrender.com
**API docs (Swagger UI):** https://ajo-api-p1xw.onrender.com/swagger-ui.html
> Hosted on Render's free tier — the service sleeps when idle, and the first request after a quiet period can take a minute or more while it wakes up.

---

## What is Ajo?

A group of people agree to contribute a fixed amount every month. Each month, one member collects the entire pot. With **N members**, the circle runs for **N months**, and everyone collects exactly once.

Example — 5 members contributing ₦20,000 monthly:

| Month | Everyone pays | Collector receives |
|-------|---------------|--------------------|
| 1     | ₦20,000 each  | Member A — ₦100,000 |
| 2     | ₦20,000 each  | Member B — ₦100,000 |
| …     | …             | …                  |
| 5     | ₦20,000 each  | Member E — ₦100,000 |

There is no interest and no fee. Ajo moves money through *time*, not between people: whoever collects early is effectively borrowing from the group interest-free, and whoever collects last has been lending. At the end of a round, every member has paid in exactly what they took out.

The rules in this project come from real Ajo groups, including:

- Payouts happen on a fixed date at the end of each month
- A member in an emergency can collect early by swapping positions with a later member who agrees — they keep contributing as before
- A member who leaves **before** collecting is refunded what they've paid in
- A member who leaves **after** collecting owes what they took beyond what they paid, and can't leave until it's repaid
- A replacement can buy into a leaver's slot by paying the leaver's contributed total
- A member who misses a month and later collects has the missed amount withheld from their payout
- New members take a late payout position in their first round

## Project status

The domain is complete, tested and deployed. Every rule above is implemented end to end, and the core money invariant — at the close of a round, what each member contributed equals what they collected — is asserted by tests, including a round with a defaulter in the middle of it.

- [x] Double-entry ledger — append-only journal, a single posting method that rejects unbalanced entries
- [x] Authentication — phone number + password, JWT access tokens, refresh-token rotation, `GET /me`
- [x] Phone verification by one-time SMS code; creating or joining a group requires a verified phone
- [x] Password reset by one-time SMS code, built so no response reveals whether a number has an account
- [x] Groups and membership — invites by phone number, admin and member roles
- [x] Rounds, participants and cycles — the full payout schedule is generated when a round activates
- [x] Contributions — idempotency keys, one contribution per member per cycle
- [x] Payouts — capped by what the pool actually holds, with shortfall claims when a pot comes up short
- [x] Position swaps — mutual consent, safe under concurrent acceptance
- [x] Exits — gated on what the leaver owes or is owed; debts block exit until repaid
- [x] Buy-ins — a replacement takes over a leaver's slot and history
- [x] Vacant-cycle settlement — a leaver's unclaimed cycle funds their refund and the shortfalls they caused
- [x] Arrears netting — missed contributions are withheld from the defaulter's own payout
- [x] OpenAPI docs with Swagger UI, and one consistent error format (RFC 9457 Problem Details) everywhere
- [x] CI on every push and pull request (GitHub Actions, full test suite against real Postgres)
- [x] Dockerised and deployed (Render + Neon Postgres), with health probes

By the numbers: **326 tests** across 31 test classes, **17 Flyway migrations**, and **64 documented endpoints**.

### What is and isn't real yet

Two things are deliberately stubbed, and it's worth being plain about them:

- **No SMS is actually sent.** `SmsSender` has a single implementation, `LoggingSmsSender`, which writes each message — including the one-time code — to the application log. It exists so the whole verification and reset flow can be built and tested; it must be replaced by a real SMS provider before any real users.
- **No money actually moves.** A contribution or payout is a recorded ledger movement — a member (or an admin, for cash) records that it happened, and the ledger posts it. There is no payment provider yet; Paystack is still to come.

This is the order things were meant to be built in. The ledger, the rules and the invariant were built and proven first, against a stub, so that when real money is wired in it arrives into a system whose bookkeeping is already known to be correct.

See the [Roadmap](#roadmap) below for what's left.

## Tech stack

| Area | Choice |
|------|--------|
| Language / framework | Java 26, Spring Boot 4.1.1 |
| Database | PostgreSQL (Neon), migrations with Flyway |
| Persistence | Spring Data JPA / Hibernate |
| Security | Spring Security, stateless, JWT (JJWT 0.13.0) |
| Mapping | MapStruct 1.6.3 + Lombok |
| API docs | springdoc-openapi 3.1.1 (OpenAPI 3.1 + Swagger UI) |
| Testing | JUnit 5, Testcontainers (real Postgres in tests) |
| CI | GitHub Actions |
| Deployment | Docker on Render |

## Design decisions

These are the choices that shape the project — and the reasoning behind them. [DESIGN.md](DESIGN.md) goes deeper into each, with worked examples and the traps found along the way.

**Double-entry ledger for all money movement.**
Balances are never stored as a single editable number. Every contribution, payout, and refund is a journal entry whose debits and credits must be equal, and entries are append-only — mistakes are corrected with reversing entries, never edits. This makes every naira traceable and lets tests assert that the books always balance.

**Money in kobo, as integers.**
All amounts are stored as `BIGINT` in kobo (₦1 = 100 kobo). No floating-point arithmetic touches money.

**Idempotent money endpoints.**
Contributions, payouts, repayments and buy-ins all require an `Idempotency-Key`. Retrying the same request (a flaky network, a double-tap) returns the original result instead of posting twice. The database backs this up: a key can be used only once, a member can contribute to a cycle only once, and a cycle can be paid out only once.

**Row locking where money changes hands.**
Payouts, position swaps, exits, buy-ins and settlement take a database row lock on the cycles involved, so two simultaneous requests can't both see the "old" state and double-post. Contributions don't need one — the one-per-member-per-cycle constraint already makes a duplicate impossible.

**The payout is capped by what the pool holds.**
The platform never pays out money it did not receive. If nine of ten members paid, the beneficiary gets nine shares, and a shortfall claim records the group's debt to them. The amount is always computed server-side — never accepted from the client. This one rule is what makes the ledger load-bearing rather than decorative.

**A vacant cycle settles exactly what it owes.**
When a member leaves before collecting and nobody replaces them, their cycle keeps collecting from everyone else and pays out to nobody. That unclaimed pot is exactly enough to refund the leaver and cover the shortfalls their absence caused — not by coincidence of the numbers, but as an identity that holds for any group size, position and exit month. Settlement pays the refund first, then the claims oldest first, and can run again as later claims arrive.

**Arrears are withheld from the defaulter's own payout.**
A member who misses a month and later collects has the missed amount withheld, and that money settles the claims their absence caused. It moves the risk to where the group already holds the defaulter's money: someone who misses payments *before* collecting is barely a risk at all. Arrears are counted as missed cycles, not a subtracted total, and a member's own cycle is excluded so nobody is ever charged twice for the same missed month.

**Position swaps under concurrency: pessimistic locks and deferred constraints.**
Two members accepting conflicting swaps at the same moment must not both succeed. Every path that changes a position locks the cycles involved, always in the same order, so concurrent swaps queue instead of racing or deadlocking. And because exchanging two positions briefly breaks the "each member collects exactly once" uniqueness rule mid-update, those constraints are declared deferrable and checked once, at commit, against the finished state.

**Phone number as identity.**
Users sign in with their phone number (normalised to E.164), matching how Nigerian fintech apps work. Email is optional. Phone verification and password reset use SMS one-time codes — stored only as BCrypt hashes, rate-limited, and capped on attempts. Password reset returns the same response whether or not a number has an account, and takes the same work either way, so it can't be used to discover who is registered.

**Stub providers first.**
SMS sits behind an interface (`SmsSender`) with a logging stub, and payments are recorded as ledger movements with no payment provider yet. The core logic is built and tested without external services; a real SMS provider and Paystack plug in later without touching the business rules.

**Web client, not a native app.**
The frontend will be a Next.js web app (later a PWA), keeping the focus on backend correctness.

## Running locally

### Prerequisites

- Java 26
- Docker
- A PostgreSQL database (local install, Docker, or a Neon dev branch)

### 1. Configure environment

Copy `.env.example` to `.env` in the project root (it's git-ignored) and fill it in:

```env
DB_URL=jdbc:postgresql://localhost:5432/ajo
DB_USERNAME=postgres
DB_PASSWORD=your-password
JWT_SECRET=<output of: openssl rand -base64 64>
CORS_ALLOWED_ORIGINS=http://localhost:3000
```

> `JWT_SECRET` must be base64 and decode to at least 256 bits. There is no default: the app refuses to start without a valid one.

> The URL must be in JDBC form (`jdbc:postgresql://host:port/db`). Keep the username and password in their own variables — don't put them inside the URL.

### 2. Run with Maven

```bash
./mvnw spring-boot:run
```

Flyway runs the migrations automatically on startup. The API docs are then at http://localhost:8080/swagger-ui.html, and one-time SMS codes appear in the application log.

### 3. Or run with Docker

```bash
docker build -t ajo-api .
docker run --env-file .env \
  -e DB_URL=jdbc:postgresql://host.docker.internal:5432/ajo \
  -p 8080:8080 ajo-api
```

Inside a container, `localhost` refers to the container itself — use `host.docker.internal` to reach a database on your machine. On Linux, also add `--add-host=host.docker.internal:host-gateway`.

### Running tests

```bash
./mvnw test
```

Tests use Testcontainers, so Docker must be running. They need no `.env` and no secrets — the test profile supplies a dummy signing key, and every integration test runs against a real Postgres container.

## Deployment

The API runs as a Docker web service on Render, connected to a Neon Postgres database. Configuration comes entirely from environment variables set in Render — the `.env` file is never deployed.

| Variable | Description |
|----------|-------------|
| `DB_URL` | JDBC URL, e.g. `jdbc:postgresql://<host>/<db>?sslmode=require` |
| `DB_USERNAME` | Database user |
| `DB_PASSWORD` | Database password |
| `JWT_SECRET` | Base64-encoded signing key, at least 256 bits (`openssl rand -base64 64`). Required |
| `CORS_ALLOWED_ORIGINS` | Comma-separated browser origins allowed to call the API, e.g. `https://ajo.example.com`. Defaults to `http://localhost:3000` |
| `PORT` | Set automatically by Render |

Render's health check path is `/actuator/health/liveness`. It does not touch the database, so a Neon cold start can't fail a deploy or trigger a restart. `/actuator/health` includes the database check and shows the real status.

## Roadmap

What's genuinely left:

1. **A real SMS provider** — replacing `LoggingSmsSender`, sending asynchronously so a slow provider can't reintroduce a timing difference into password reset
2. **Login rate limiting** — the one remaining brute-force surface; one-time codes are already rate-limited and attempt-capped
3. **Admin role promotion** — today every group has exactly one admin, its creator, who can therefore never leave
4. **Concurrency tests for contributions and payouts** — they are protected by idempotency keys, unique constraints and row locks, but only swaps, registration and the one-time-code flows are currently tested under concurrent requests
5. **Pagination** — list endpoints currently return everything
6. **Frontend** — Next.js web app, later a PWA
7. **Real money** — Paystack for collections (with webhooks), Paystack Transfers for payouts to members' bank accounts, and reconciliation against the ledger

**Known limits**, recorded in [DESIGN.md](DESIGN.md#15-known-limits): payouts draw on the whole round pool rather than a separate pot per cycle; beyond blocking their exit, there is no automatic recovery from a member who defaults after collecting (guarantors are the obvious next step); settlement is triggered by an admin, not a scheduled job; and there is a single currency. Access tokens already issued also remain valid until they expire (15 minutes) after a password reset — the reset revokes every refresh token, but short-lived access tokens are stateless.

**Deliberately deferred:** Google OAuth (phone is the primary identity), email verification, biometrics, and a native mobile app.

## Author

**theNinjaDev** — frontend developer moving into full-stack, focused on Java/Spring Boot and fintech.
