# Ajo API

A backend for **Ajo** — a digital version of the rotating savings circle used across Nigeria (also known as *esusu* or *adashe*).

**Live API:** https://ajo-api-p1xw.onrender.com
> Hosted on Render's free tier — the first request after a period of inactivity can take 30–60 seconds while the service wakes up.

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

The rules in this project come from real Ajo groups, including:

- Payouts happen on a fixed date at the end of each month
- A member in an emergency can collect early, but must keep contributing
- A member who leaves **before** collecting is refunded what they've paid in
- A member who leaves **after** collecting owes the remaining contributions
- A replacement can buy into a vacant slot by paying the leaver's contributed total
- New members take a late payout position in their first round

## Project status

🚧 **Early development.** The foundation is in place and deployed; the money features are being built next.

- [x] Project scaffold, database migrations, and build pipeline
- [x] Dockerised and deployed (Render + Neon Postgres)
- [ ] Authentication (phone number + password, JWT)
- [ ] Phone verification and password reset via SMS OTP
- [ ] Double-entry ledger
- [ ] Groups, cycles, contributions, and payouts

See the [Roadmap](#roadmap) below for the full plan.

## Tech stack

| Area | Choice |
|------|--------|
| Language / framework | Java 26, Spring Boot 4.1.1 |
| Database | PostgreSQL (Neon), migrations with Flyway |
| Persistence | Spring Data JPA / Hibernate |
| Mapping | MapStruct 1.6.3 + Lombok |
| Auth | JWT (JJWT 0.13.0) |
| Testing | JUnit 5, Testcontainers (real Postgres in tests) |
| Deployment | Docker on Render |

## Design decisions

These are the choices that shape the project — and the reasoning behind them.

**Double-entry ledger for all money movement.**
Balances are never stored as a single editable number. Every contribution, payout, and refund is a journal entry whose debits and credits must be equal, and entries are append-only — mistakes are corrected with reversing entries, never edits. This makes every naira traceable and lets tests assert that the books always balance.

**Money in kobo, as integers.**
All amounts are stored as `BIGINT` in kobo (₦1 = 100 kobo). No floating-point arithmetic touches money.

**Idempotent payment endpoints.**
Contribution requests carry an `Idempotency-Key`. Retrying the same request (a flaky network, a double-tap) returns the original result instead of charging twice. Payouts are protected the same way so a retried job can never pay a round twice.

**Row locking for concurrent writes.**
Operations that change a group's money take a database row lock, so two simultaneous requests can't both see the "old" state and double-post.

**Phone number as identity.**
Users sign in with their phone number (normalised to E.164), matching how Nigerian fintech apps work. Email is optional. Phone verification and password reset use SMS one-time codes.

**Stub providers first.**
Payments and SMS sit behind interfaces (`PaymentProvider`, `SmsSender`) with stub implementations. The core logic is built and tested without external services; Paystack and a real SMS provider plug in later without touching business code.

**Web client, not a native app.**
The frontend will be a Next.js web app (later a PWA), keeping the focus on backend correctness.

## Running locally

### Prerequisites

- Java 26
- Docker
- A PostgreSQL database (local install, Docker, or a Neon dev branch)

### 1. Configure environment

Create a `.env` file in the project root (it's git-ignored):

```env
DB_URL=jdbc:postgresql://localhost:5432/ajo
DB_USERNAME=postgres
DB_PASSWORD=your-password
JWT_SECRET=a-long-random-string
```

> The URL must be in JDBC form (`jdbc:postgresql://host:port/db`). Keep the username and password in their own variables — don't put them inside the URL.

### 2. Run with Maven

```bash
./mvnw spring-boot:run
```

Flyway runs the migrations automatically on startup.

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

Tests use Testcontainers, so Docker must be running.

## Deployment

The API runs as a Docker web service on Render, connected to a Neon Postgres database. Configuration comes entirely from environment variables set in Render — the `.env` file is never deployed.

| Variable | Description |
|----------|-------------|
| `DB_URL` | JDBC URL, e.g. `jdbc:postgresql://<host>/<db>?sslmode=require` |
| `DB_USERNAME` | Database user |
| `DB_PASSWORD` | Database password |
| `JWT_SECRET` | Secret for signing tokens |
| `PORT` | Set automatically by Render |

## Roadmap

1. **Deployment hygiene** — health check endpoint, env-based CORS and secrets, CI running tests on every push
2. **Authentication** — register and log in with phone number + password, JWT, `/me` endpoint
3. **Phone verification & password reset** — one SMS OTP service (hashed codes, expiry, attempt and rate limits) behind a stub `SmsSender`; only verified users can create or join groups
4. **Double-entry ledger** — append-only journal, a single posting method that rejects unbalanced entries, invariant tests
5. **Groups & cycles** — group setup, payout positions, round generation, and a round state machine
6. **Contributions** — idempotency keys, row locking, and concurrency tests proving a retried request is never charged twice
7. **Payouts** — scheduled payout on the fixed end-of-month date, posted exactly once per round; the pot must end at zero
8. **Real-world rules** — emergency early collection, leaving before or after collecting, and replacement buy-ins
9. **API polish** — OpenAPI docs, pagination, transaction history
10. **Frontend** — Next.js web app, later a PWA
11. **Real money** — Paystack for collections (with webhooks), Paystack Transfers for payouts to members' bank accounts, and reconciliation against the ledger

**Deliberately deferred:** Google OAuth (phone is the primary identity), email verification, biometrics, and a native mobile app.

## Author

**theNinjaDev** — frontend developer moving into full-stack, focused on Java/Spring Boot and fintech.
