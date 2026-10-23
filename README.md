# Car Reservation Service

A Spring Boot microservice for time-slot based car reservations over a fleet of 10,000 cars. The
focus is **concurrency** and **data integrity**: a PostgreSQL exclusion constraint guarantees that
no two confirmed reservations of the same car overlap. When many users try to book the same car at
the same moment, exactly one succeeds and the others get a clean `409 Conflict`. Integration tests
prove this against a real database.

How each requirement of the assignment is met:

| Requirement | How | Proven by |
|---|---|---|
| No overlapping confirmed reservations of a car | A PostgreSQL exclusion constraint on `(car_id, period) WHERE status = 'CONFIRMED'` | Schema tests, with no application code involved |
| Concurrent bookings: only one succeeds, the others fail gracefully | The constraint, plus a per-car row lock so that losers get `409 RESERVATION_CONFLICT` instead of a deadlock | Concurrency tests: of 32 parallel bookings, exactly 1 wins |
| Cancel only while the start is at least 30 minutes away | `Reservation.cancel`, with "now" from an injected `Clock`; optimistic locking for concurrent cancellations | Unit tests on the exact boundary; a concurrent-cancellation test |
| 2 h minimum, 24 h maximum | `BookingPeriod` validation, repeated as a database `CHECK` | Unit tests to the microsecond; schema tests |
| Clear status codes for every failure | RFC 9457 Problem Details with a stable `code` | Web-slice tests for every status and `code` |
| A fleet of 10,000 cars | An anti-join that reuses the constraint's GiST index: about 1 ms for the first page | [Query plans and measurements](docs/performance/availability-query.md) |

**Stack:** Java 25 · Spring Boot 3.5 · PostgreSQL 18 · Hibernate 6.6 · Liquibase (XML changelogs) ·
OpenAPI 3 (contract-first) · Testcontainers · Maven · Docker

- [Quick start](#quick-start)
- [API](#api)
- [Assumptions](#assumptions)
- [Design decisions](#design-decisions)
- [Testing](#testing)
- [Development](#development)
- [Trade-offs and known limitations](#trade-offs-and-known-limitations)
- [Future improvements](#future-improvements)
- [Troubleshooting](#troubleshooting)
- [Project layout](#project-layout)

## Quick start

Only Docker (23+, with Compose v2) is needed. The image is built from source inside Docker.

```bash
docker compose up -d --build   # the service on localhost:8080, PostgreSQL 18 on localhost:15432
scripts/smoke-test.sh          # Linux, macOS, Git Bash
```

On Windows without bash, run `powershell -ExecutionPolicy Bypass -File scripts\smoke-test.ps1`.

- **Swagger UI:** <http://localhost:8080/swagger-ui> (the root URL redirects there)
- **OpenAPI spec:** <http://localhost:8080/openapi/car-reservation-api.yaml>
- **Health check:** <http://localhost:8080/actuator/health>

Other host ports: `APP_PORT=9090 docker compose up -d` (or set it in `.env`), then
`scripts/smoke-test.sh http://localhost:9090` (`-BaseUrl` in PowerShell); the links follow the port.
`POSTGRES_PORT` does the same for the database.

On startup, Liquibase creates the schema and seeds the 10,000 cars.

The smoke test runs the main flow over HTTP against a running instance:
- books an available car;
- checks that an overlapping booking is rejected, that the car disappears from availability, and that
  the reservation is visible only to its owner;
- cancels it.

`docker compose down -v` removes the database volume as well.

## API

The API is defined **contract-first** in
[`car-reservation-api.yaml`](src/main/resources/static/openapi/car-reservation-api.yaml)
(OpenAPI 3.0.3). The spec is the source of truth:
- the build generates the server interfaces and DTOs from it, and the controllers implement those
  interfaces, so the code cannot drift from the contract;
- the running service serves the same file to Swagger UI.

| Method | Path | Success | Purpose |
|---|---|---|---|
| `GET` | `/api/v1/cars?page&size` | 200 | The fleet, ordered by license plate |
| `GET` | `/api/v1/cars/availability?from&to&page&size` | 200 | Cars with no confirmed reservation overlapping `[from, to)` |
| `POST` | `/api/v1/reservations` | 201 + `Location` | Book a car for a slot |
| `GET` | `/api/v1/reservations/{id}?userId` | 200 | Read your own reservation |
| `POST` | `/api/v1/reservations/{id}/cancel` | 200 | Cancel your own reservation |

Lists are paginated: `size` defaults to 50 and is capped at 200. Responses include `hasNext` but no
total count, because counting all available cars would rerun the availability query over the whole
fleet on every page.

### Example

```bash
# a car that is free on 15 Jan 2030, 08:00-12:00 UTC
curl 'http://localhost:8080/api/v1/cars/availability?from=2030-01-15T08:00:00Z&to=2030-01-15T12:00:00Z&size=1'

# book it (carId from the previous response; userId is any UUID, see Assumptions)
curl -i -X POST http://localhost:8080/api/v1/reservations -H 'Content-Type: application/json' -d '{
  "carId": "<carId>", "userId": "3f2c1a9e-6b7d-4c1e-9a55-2d8f0e4b7c61",
  "startTime": "2030-01-15T08:00:00Z", "endTime": "2030-01-15T12:00:00Z" }'

# the same request again: 409 RESERVATION_CONFLICT
# cancel it
curl -X POST http://localhost:8080/api/v1/reservations/<reservationId>/cancel \
  -H 'Content-Type: application/json' -d '{ "userId": "3f2c1a9e-6b7d-4c1e-9a55-2d8f0e4b7c61" }'
```

### Errors

Every error uses [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) Problem Details
(`application/problem+json`). Each one has a stable, machine-readable `code`, and validation errors
also list field-level `errors`:

```json
{
  "type": "/problems/reservation-conflict",
  "title": "Time slot unavailable",
  "status": 409,
  "detail": "Car 01a16342-… is already reserved for a time slot overlapping 2030-01-15T08:00:00Z - 2030-01-15T12:00:00Z",
  "instance": "/api/v1/reservations",
  "code": "RESERVATION_CONFLICT"
}
```

The status codes follow one rule: **400** means the request is malformed, **422** means it is
well-formed but breaks a business rule, and **409** means it is valid but the current state of the
resource forbids it.

| Status | `code` | When |
|---|---|---|
| 400 | `MALFORMED_REQUEST` | Unparseable JSON, a bad UUID, a time without an offset, or a numeric epoch |
| 400 | `VALIDATION_FAILED` | Missing or out-of-range fields (listed in `errors`) |
| 422 | `INVALID_BOOKING_PERIOD` | Duration outside 2–24 h, or a start that is not in the future |
| 422 | `INVALID_SEARCH_WINDOW` | `from >= to`, or a window longer than 31 days |
| 404 | `CAR_NOT_FOUND` / `RESERVATION_NOT_FOUND` | The car or reservation is unknown, or the reservation belongs to someone else |
| 409 | `RESERVATION_CONFLICT` | The slot overlaps a confirmed reservation of the car, including when a concurrent request won the race |
| 409 | `RESERVATION_ALREADY_CANCELLED` | The reservation is already cancelled |
| 409 | `CANCELLATION_WINDOW_CLOSED` | The reservation starts in less than 30 minutes |
| 409 | `CONCURRENT_MODIFICATION` | A concurrent cancellation won, or the car stayed locked longer than the 2 s lock timeout. Safe to retry. |
| 503 | `SERVICE_UNAVAILABLE` | The database is unreachable or a statement timed out |
| 500 | `INTERNAL_ERROR` | Anything unexpected. It is logged with its full cause, and the response never exposes stack traces or SQL. |

## Assumptions

The assignment leaves some points open. These are the interpretations chosen:

| Question | Decision |
|---|---|
| "A list of all available cars" | `GET /cars` returns the whole fleet, and `GET /cars/availability` returns the cars free in a given interval. "Available right now" is just an availability query starting now, so it needs no separate endpoint. |
| Where cars come from | The API has no endpoint for creating cars, so a Liquibase changeset seeds 10,000 of them. Fleet master data belongs to a different service (see [Service boundary](#service-boundary)). |
| 2 h minimum, 24 h maximum | Measured as elapsed time, with both bounds inclusive: `2h ≤ end − start ≤ 24h`. See [Time handling](#time-handling-and-dst) for what this means around DST changes. |
| Back-to-back bookings (10:00–12:00, then 12:00–14:00) | Allowed: intervals are half-open, `[start, end)`. |
| "At least 30 minutes in the future" | Inclusive: cancelling is allowed while `now ≤ startTime − 30 min`, so cancelling exactly 30 minutes before the start still works. |
| Cancelling twice | `409 RESERVATION_ALREADY_CANCELLED`. A repeat is reported, not silently ignored. |
| Availability search window | `from < to` and at most 31 days, to bound the query. The 2–24 h rule applies to bookings, not to searches. |
| User identity | There is no authentication in scope, so the caller sends `userId`. Reads and cancellations check ownership and answer **404** to anyone else, so outsiders cannot even learn that a reservation exists. [Future improvements](#future-improvements) describes how the token would replace this field. |

## Design decisions

### Service boundary

A service boundary should follow a transactional consistency boundary. The central rule here is
"no two confirmed reservations of one car may overlap", and enforcing it needs the reservations and
the set of bookable cars in **one database**. The availability query needs the same two things.
So this service owns:
- **reservations**;
- **the bookable car inventory** (`car` table): the minimal data needed to book a car.

Fleet master data (specifications, photos, branches, maintenance planning), identity, pricing,
payments and notifications are deliberately outside this service. [Future improvements](#future-improvements)
describes how they would connect. The database is private to the service: other services integrate
only through the REST contract (or, later, events). That private ownership is what makes a
database-level invariant a legitimate design choice and not shared-database coupling.

### Preventing double booking: an exclusion constraint

```sql
ALTER TABLE reservation ADD COLUMN period tstzrange
    GENERATED ALWAYS AS (tstzrange(start_time, end_time, '[)')) STORED;
ALTER TABLE reservation ADD CONSTRAINT ex_reservation_no_overlap
    EXCLUDE USING gist (car_id WITH =, period WITH &&) WHERE (status = 'CONFIRMED');
```

The rule is declared once, in the database. It therefore holds for every code path, every isolation
level and every writer, including future ones. Approaches considered:

| Approach | Verdict |
|---|---|
| Check with `SELECT`, then `INSERT` | Rejected. Two transactions can both see the slot as free and both insert; this is the classic race. |
| `SERIALIZABLE` + check-then-insert | Correct, but needs retry loops for serialization failures, aborts transactions under load even when there is no real conflict, and every code path must remember to use it. |
| Pessimistic lock on the car, then check, then insert | Correct only as long as every code path takes the lock. One path that forgets breaks the rule silently. |
| PostgreSQL 18 `UNIQUE (car_id, period WITHOUT OVERLAPS)` | Cannot be partial, so cancelled reservations would keep blocking their slot. |
| **Exclusion constraint `WHERE status = 'CONFIRMED'`** | **Chosen.** It is race-free at the default READ COMMITTED isolation level, and cancelling a reservation frees its slot immediately. |

The service inserts with `saveAndFlush`, so a violation surfaces inside the service method. It then
maps SQLSTATE `23P01` to `ReservationConflictException`, which becomes 409. Further `CHECK`
constraints (status values, and the 2–24 h duration) repeat the domain validation on purpose: if
anything ever bypasses the application, the database still refuses invalid data.

### Concurrency: a finding, and the per-car lock

With the constraint alone (in a local run, before the fix below), the concurrency tests were *correct*: exactly one of 32 simultaneous
identical bookings won, every time. But almost every loser failed with **`40P01 deadlock_detected`**
after PostgreSQL's 1-second `deadlock_timeout`, not with a clean `23P01`. A burst took about
30 seconds. The cause is how exclusion constraints work: each insert first adds its index entry and
then looks for conflicts. Two in-flight conflicting inserts therefore end up waiting on each other,
and the deadlock detector has to break the cycle. Unique B-tree indexes handle this case specially;
exclusion constraints do not.

The fix is in [`CarLookup.lockForBooking`](src/main/java/com/example/carreservation/car/CarLookup.java):
the booking transaction starts by locking the car's row with `SELECT … FOR NO KEY UPDATE`.

- Bookings of the **same car** are serialized: losers wait milliseconds, see the winner's committed
  row, and fail fast with `23P01`, which becomes 409.
- Bookings of **different cars** never block each other. A test covers this.
- `FOR NO KEY UPDATE`, not `FOR UPDATE`, so other transactions' foreign-key checks on the car row are
  not blocked.
- The exclusion constraint remains the actual guarantee. The lock adds liveness and clean errors on
  top of it.

Every wait is bounded. Each connection sets `lock_timeout = 2s`, so a booking stuck behind a stalled
transaction fails with a retryable `409 CONCURRENT_MODIFICATION` and does not hold a pooled
connection indefinitely. `statement_timeout = 10s` stops runaway statements.

**Cancellation** uses optimistic locking (`@Version`). Of two concurrent cancellations, exactly one
wins and the other gets `409 CONCURRENT_MODIFICATION`. The rules themselves (owner only, not already
cancelled, at least 30 minutes before the start) live in `Reservation.cancel`, so plain unit tests
cover them.

### Time handling and DST

- **Times are `Instant` in Java and `timestamptz` in the database.** A reservation is a pair of
  absolute points on the timeline, so time zones and DST cannot affect the stored values or the
  comparisons.
- **Input must carry an explicit offset** (`Z` or `+02:00`). A value without an offset is ambiguous
  and is rejected with 400.
  - So is a numeric epoch. Jackson accepts epochs by default, so a strict deserializer
    (`StrictInstantDeserializer`) closes that gap.
  - Output is always UTC.
- **Elapsed time is what counts.** A booking from 10:00 to 10:00 the next day in local time lasts:
  - **23 h** across the spring-forward night, which is **accepted**;
  - **25 h** across the fall-back night, which is **rejected (422)**.

  This is deliberate: the car really is occupied for that many hours, and it is the only reading under
  which the Java validation and the database `CHECK` agree.
- **No time zone is special.** The service never converts to local time, so European, US and other
  DST rules (or none) all behave the same way. Both transitions are unit-tested in `Europe/Belgrade`
  and `America/New_York`, whose clocks change on different dates.
- **Precision is microseconds.** Instants are truncated to microseconds before validation, so the value
  that is validated is exactly the value PostgreSQL stores.
- **"Now" comes from an injected `Clock`.** Tests can therefore check the 30-minute cancellation
  boundary exactly.

### Availability at fleet scale

The availability query is a `NOT EXISTS` anti-join that reads cars in license-plate order and stops
after one page. It needed **no new index**. It reuses the unique index on `license_plate` and the
partial GiST index behind the exclusion constraint, which covers exactly
`(car_id, period) WHERE status = 'CONFIRMED'`. Using the same half-open range as the constraint
means search and booking can never disagree about what overlapping means.

With 500,000 reservations in the database, the first page of 50 cars takes about **1 ms**, and a deep
page (offset 2,500) about 50 ms. The [query plans and measurements](docs/performance/availability-query.md)
include the data-generation script.

### Code structure

The code is organised **package-by-feature**: `car`, `reservation`, and `common` for the
cross-cutting pieces (errors, JSON, persistence, time, web). Each feature package holds its entity,
repository, service and controller.

- **Domain rules live in plain Java.** `BookingPeriod` (a record) validates duration and precision,
  and `Reservation` enforces booking, ownership and cancellation rules. Both are unit-tested without
  Spring or a database. Services only orchestrate.
- **The dependency between the modules is one-way.** `reservation` looks cars up only through
  `CarLookup`, and holds `carId` rather than a `@ManyToOne Car`. Availability needs reservation data,
  so `car` defines an interface for it (`CarAvailability`) that `reservation` implements. `car`
  never depends on `reservation`.
- **Identity is a parameter, never read from the request.** The services take `userId` as an
  argument, so adding authentication changes only the controllers.
- **Generated code stays at the edge.** Generated DTOs appear only in controllers and mappers; the
  domain never imports them.
- **No hexagonal architecture.** Ports, adapters and mapping layers for five endpoints and two tables
  would add files, not clarity. The one port that pays for itself, `CarAvailability`, exists.

### Persistence

- **Data model.** Both tables follow the assignment's entities:
  - `car`: `id` (UUID), `make`, `model`, `license_plate` (unique).
  - `reservation`: `id`, `car_id` (foreign key to `car`), `user_id`, `start_time`, `end_time` (all
    `timestamptz`), and `status` (`CONFIRMED` or `CANCELLED`).
  - `reservation` also has `created_at`, `cancelled_at`, a `version` column for optimistic locking,
    and the generated `period` range that the exclusion constraint and the availability query use.
- **Liquibase owns the schema** (XML changelogs under `src/main/resources/db/changelog`), and
  Hibernate only validates it (`ddl-auto: validate`).
  - Native change types are used wherever Liquibase Community has them. PostgreSQL-specific DDL (the
    range column, the exclusion constraint, the `CHECK`s) is plain `<sql>` with an explicit rollback.
  - Applied changesets are never edited; changes go into new changesets, such as `003`.
- **IDs are UUIDv7**, which are time-ordered and keep B-tree inserts local.
  - Hibernate 6.6 cannot generate v7 itself, so a small `UuidV7Generator` adapter wraps JUG.
  - The seed changeset uses PostgreSQL 18's native `uuidv7()`, so seeded and application-generated
    keys have the same shape. This is why **PostgreSQL 18+ is required**.
- **The 10,000-car seed is an ordinary changeset that runs in every environment.** The service is
  unusable without cars, and the API cannot create them.

## Testing

```bash
./mvnw test     # unit and web-slice tests, no Docker needed
./mvnw verify   # everything, including the Testcontainers integration tests (Docker required)
```

Integration tests use **Testcontainers with real PostgreSQL 18**, not H2: H2 has no exclusion
constraints and no `tstzrange`, so the core guarantee could not be tested there. All integration
tests share one Spring context and therefore one container.

| Layer | Examples |
|---|---|
| Domain unit | The 2 h / 24 h bounds to the microsecond; DST 23 h accepted and 25 h rejected (EU and US); the 30-minute cancellation boundary; ownership |
| Web slice (`@WebMvcTest`) | Every error status and `code`; rejection of missing offsets and numeric epochs; the page-size cap; the `Location` header |
| Schema | The database constraints on their own, with no application code: overlap rejected, back-to-back allowed, cancelled rows ignored, duration `CHECK` |
| Integration | The reservation lifecycle over HTTP; availability correctness (half-open intervals, cancelled reservations, paging without gaps or duplicates) |
| **Concurrency** | Most scenarios fire 32 requests in parallel transactions, released together by a latch, and assert both the outcomes and the database state. The last two hold the car lock in a second transaction. Scenarios: <ul><li>32 identical and 32 partially overlapping bookings: exactly 1 succeeds, 31 conflict</li><li>32 different cars, and 32 disjoint slots of one car: all succeed</li><li>concurrent cancellations: exactly 1 wins</li><li>a locked car does not block other cars</li><li>a booking behind a stalled transaction fails after the lock timeout instead of hanging</li></ul> |

There are 94 unit and web-slice tests and 49 integration tests. `./mvnw verify` also writes a JaCoCo
coverage report to `target/site/jacoco` (generated code excluded). It shows **96 % line and 77 % branch coverage**, with
99–100 % of lines in the `car` and `reservation` packages.

## Development

Prerequisites: JDK 25 and Docker. Maven is not required; the wrapper (`./mvnw`) downloads it.

```bash
docker compose up -d postgres   # PostgreSQL 18 on localhost:15432 (avoids clashing with a local install on 5432)
./mvnw spring-boot:run          # or run CarReservationApplication from the IDE
```

`./mvnw spring-boot:test-run` instead starts the application against a throwaway PostgreSQL
container, with no compose needed.

The database connection can be configured with `DB_URL`, `DB_USERNAME` and `DB_PASSWORD`, and the
HTTP port (8080) with `SERVER_PORT`. Run
`./mvnw compile` once after cloning so that the IDE can resolve the generated API types (under
`target/generated-sources/openapi`). The spec can be linted with `npx @redocly/cli lint` (needs
Node.js).

The Docker image is a multi-stage build:
- dependencies are cached between builds;
- the jar is split into Spring Boot layers, so a code change rebuilds only the application layer;
- the runtime is `eclipse-temurin:25-jre`, running as a non-root user;
- the container has a healthcheck on the readiness probe.

## Trade-offs and known limitations

- **Spring Boot 3.5 is out of open-source support.** The assignment calls for Spring Boot 3, and 3.5.16
  is the last 3.x release, but free support for Boot 3.5 and Spring Framework 6.2 ended on
  30 June 2026.
  - Until the upgrade, individual libraries can still be raised within their compatible lines (for
    example Hibernate 6.6.x patches or the PostgreSQL JDBC driver).
  - The upgrade path is Boot 4 via the OpenRewrite migration recipes. The upgrade also retires
    `UuidV7Generator` in favour of Hibernate 7's built-in `@UuidGenerator(style = VERSION_7)`, with no
    database change.
- **`userId` is trusted from the request.** That is acceptable only because authentication is out of
  scope. See below.
- **The application clock decides "now".** With several instances, clock skew (milliseconds under NTP)
  can shift the 30-minute cancellation boundary by that much.
- **Paging is offset-based.** That is fine for 10,000 cars: a deep page costs about 50 ms. Keyset
  paging would be the next step if the fleet grew by orders of magnitude.
- **Waiting bookings hold a database connection.** A booking that waits for the car lock keeps its
  connection for up to 2 s. Different cars never wait for each other, so this only matters when many
  requests target the same car at the same time.

## Future improvements

These stay **within this service's boundary**. Capabilities that belong to other services are listed
separately at the end.

1. **Authentication with tokens.** An external identity provider (e.g. Keycloak) issues JWTs, and this
   service becomes an OAuth2 resource server. Every caller presents a token; the flow depends on who
   is calling:
   - **customers** (a web or mobile app): authorization code with PKCE, and the customer is the
     token's `sub` claim;
   - **other services**: client credentials, authorized by scopes such as `reservations:write`;
   - **counter staff booking for a customer on site**: their own token, with an `agent` role.

   Consequences for the API:
   - Customers no longer send `userId`; it comes from the token. Agents and services name the customer
     in an optional `customerId`, which only those roles and scopes may set. Each reservation also
     records who made it (`booked_by`), for audit.
   - Static API keys are not offered: they are long-lived shared secrets with no expiry and no standard
     claims. A partner that cannot use OAuth2 can be bridged at the gateway.
   - The services already take identity as a parameter, so reading it from the token changes only the
     controllers. `customerId` and `booked_by` go further: one more service parameter and one more
     column. `user_id` stays a UUID, which matches identity providers with UUID subjects such as
     Keycloak; others would need a mapping.
   - Removing `userId` is a breaking change, so it goes into `/api/v2`, or into a deprecation window
     that rejects a body `userId` that differs from the token.
   - 401 and 403 also become Problem Details. Responses from the security filter chain need their own
     handlers for that, because they never reach `@RestControllerAdvice`.
   - Agents could read and cancel any customer's reservation, but would still be bound by the
     30-minute rule, which is a business rule, not a permission.
2. **"My reservations".** `GET /reservations` lists the caller's own reservations, paginated. It would
   bring back the `user_id` index that changeset `003` dropped while nothing queried by user.
3. **Out-of-service periods.** Maintenance windows and planned retirement dates block a car exactly
   like a booking does (a retirement date as an open-ended block).
   - They would be rows of the `reservation` table with a `kind` column, so the same exclusion
     constraint rejects conflicting inserts race-free.
   - Inserting a block takes the same car lock as a booking, so a block and a booking of the same car
     racing each other are serialized exactly like two bookings: whichever commits first wins, and the
     other gets a 409.
   - The 2–24 h duration `CHECK` would then apply to bookings only, since a maintenance window can last
     for days.
   - When a block is rejected, a follow-up query finds the affected bookings, and the 409 lists them.
   - Deciding *when* a car is serviced or retired stays with fleet management; whether that time
     conflicts with bookings is decided here.
4. **Displacing bookings.** Some blocks cannot wait (a recall, an accident), and retiring a car affects
   all of its future bookings. A forced block would run in one transaction:
   - Find the conflicting bookings and a comparable free car for each, then lock the blocked car and
     all target cars in ID order, so that two displacements cannot deadlock, and re-check under the
     locks.
   - Insert the block. Move each conflicting booking to its target car (it keeps its reservation ID,
     and the exclusion constraint re-checks the move), or cancel it with a reason (`DISPLACED`) when
     no comparable car is free.
   - Publish `ReservationDisplaced` events through the outbox (item 6), so that the customer is told
     and any refund is handled by the payment service.

   Notes:
   - "Comparable" needs the car's category and branch in the projection (item 5).
   - Whether to force a block is a fleet or operations decision; this service only carries it out
     consistently.
   - Cars are never deleted, because reservations reference them. A `CarRetired` event sets the car's
     bookable flag to false, and the same transaction displaces its future bookings. That update locks
     the car row, and the booking path reads the flag in its locking `SELECT`. So either the booking
     sees the car is retired, or the retirement sees the booking and displaces it; no booking slips
     through unnoticed.
5. **The fleet projection fed by events.** The `car` table becomes a read model of the Fleet service's
   data (`CarRegistered`, `CarUpdated`, `CarRetired`), kept up to date by an idempotent consumer.
   - It replaces the seed changeset.
   - Only data needed for booking and search is copied (id, make, model, plate, a bookable flag, and
     the category and branch that item 4 needs), not the full catalogue.
6. **Publishing reservation events.** `ReservationConfirmed` and `ReservationCancelled` are written to
   a transactional outbox in the same transaction as the reservation, so they are never lost or
   published for a rolled-back booking.
7. **Payment holds for prepaid bookings.** When payment is taken online before the booking is
   confirmed, a `PENDING_PAYMENT` status with an expiry lets the payment flow reserve the slot first.
   - Pay-at-pickup bookings skip the hold and are confirmed directly, as today. Whether a booking has
     been paid is the payment service's state, not a reservation status, so a payment taken at the
     counter changes nothing here.
   - It must join the exclusion constraint's predicate (`status IN ('CONFIRMED', 'PENDING_PAYMENT')`),
     or holds would not block the slot.
   - A constraint cannot refer to the current time, so an expired hold keeps blocking its slot until it
     is released. A scheduled job releases expired holds, and the booking path also releases a car's
     expired holds under the car lock before inserting, so nobody gets a spurious 409.
8. **No-shows.** A pay-at-pickup booking costs nothing if the customer does not turn up, but blocks the
   car for its whole slot. A `NO_SHOW` status, set by a counter agent, or automatically when no pickup
   event has arrived by the end of a grace period, would be outside the exclusion constraint's
   predicate and so free the car. Freeing
   the whole period is safe: its past part can never be booked again anyway. Deposits and no-show fees
   belong to the payment service.
9. **Idempotent booking.** An `Idempotency-Key` header on `POST /reservations` lets clients safely
   retry after a timeout without risking a duplicate booking under a new ID.
10. **Observability.** Micrometer metrics for bookings, conflicts and lock timeouts, OpenTelemetry
   tracing, and structured JSON logs.

**Outside this service:**
- **Fleet / catalogue service:** car master data, photos, branches, maintenance planning.
- **Identity provider:** users, credentials, roles, token issuing.
- **Pricing and payment:** tariffs, online and counter payments, deposits and no-show fees. Prepaid
  bookings coordinate with holds (item 7) as a saga.
- **Notifications:** emails and SMS, driven by the published events (item 6).
- **API gateway:** rate limiting and edge concerns.

None of these needs this service's database. Splitting reservations and the bookable inventory
apart, by contrast, would move the no-overlap rule across a network boundary and replace a
database guarantee with sagas and eventual consistency.

## Troubleshooting

- **The application or Testcontainers cannot reach Docker ("client version … is too old").** Newer
  Docker Engines (29+) dropped old API versions. Testcontainers 1.21.4 handles this; if your Docker
  setup still reports it, set `DOCKER_API_VERSION=1.44`.
- **Port 5432 or 8080 is already in use.** Compose publishes PostgreSQL on **15432** precisely so that
  it does not clash with a local install. Override the ports with `POSTGRES_PORT` / `APP_PORT`; for
  `spring-boot:run`, point `DB_URL` at the new port.
- **The `.ps1` smoke test reports "running scripts is disabled on this system".** Start it with
  `powershell -ExecutionPolicy Bypass -File scripts\smoke-test.ps1`.
- **Tests print `Sharing is only supported for boot loader classes because bootstrap classpath has been appended`.**
  This is harmless. The Java agents used by the tests (JaCoCo for coverage, Mockito's inline mock
  maker) extend the boot class path, so the test JVMs skip class-data sharing for application classes.
- **`docker compose up` fails with "the --mount option requires BuildKit".** Use Docker 23+ or
  Compose v2. Both enable BuildKit by default.
- **A stale schema after switching branches.** `docker compose down -v` removes the database volume;
  Liquibase recreates the schema on the next start.

## Project layout

```
src/main/java/com/example/carreservation/
├── car/            fleet listing, availability search, CarLookup (the per-car booking lock)
├── reservation/    booking, reading and cancelling; domain rules in BookingPeriod and Reservation
└── common/         error handling (Problem Details), strict JSON instants, UUIDv7, clock, Swagger UI
src/main/resources/
├── static/openapi/car-reservation-api.yaml   the API contract (generator input, served to Swagger UI)
└── db/changelog/                             Liquibase XML changelogs (schema, seed)
src/test/java/...                             unit, web-slice, schema, integration and concurrency tests
docs/performance/                             availability query plans and measurements
scripts/                                      smoke tests (bash and PowerShell)
compose.yaml, Dockerfile                      local stack and container image
```
