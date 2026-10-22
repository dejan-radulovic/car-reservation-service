# Car Reservation Service

A Spring Boot microservice for time-slot based car reservations, focused on **concurrency** and
**data integrity**: a PostgreSQL exclusion constraint guarantees that no two confirmed
reservations of the same car can overlap, even under concurrent requests.

> Work in progress. Design decisions, API documentation and trade-offs will be documented here as
> the implementation lands.

## Tech stack

Java 25 · Spring Boot 3.5 · PostgreSQL 18 · Hibernate 6.6 · Liquibase (XML changelogs) ·
Testcontainers · Maven

## Prerequisites

- JDK 25
- Docker (for the local database and for integration tests)

Maven itself is not required; the bundled wrapper (`./mvnw`) downloads it.

## Running locally

```bash
docker compose up -d     # PostgreSQL 18 on localhost:15432 (avoids clashing with a local install on 5432)
./mvnw spring-boot:run   # schema and the 10,000-car seed are applied by Liquibase on startup
```

Alternatively, `./mvnw spring-boot:test-run` starts the application against a throwaway
PostgreSQL container, without compose.

Health check: <http://localhost:8080/actuator/health>

## Data

The database schema is managed by Liquibase (XML changelogs under
`src/main/resources/db/changelog`). The API has no endpoint for creating cars, so a changeset
seeds a fleet of **10,000 cars**. It runs in every environment on purpose: the service is unusable
without cars, and Liquibase contexts would only restrict it if every deployment configured them.
The seed uses PostgreSQL 18's built-in `uuidv7()`, so **PostgreSQL 18+ is required** (pinned in
`compose.yaml` and in the tests).

## API

The API is defined **contract-first** in
[`car-reservation-api.yaml`](src/main/resources/static/openapi/car-reservation-api.yaml)
(OpenAPI 3.0.3). The build generates the server interfaces and DTOs from it
(`target/generated-sources/openapi`), and the controllers implement those interfaces, so the code
cannot drift from the contract. Run `./mvnw compile` once after cloning so that your IDE can
resolve the generated types.

Optional lint (needs Node.js): `npx @redocly/cli lint`

## Testing

```bash
./mvnw test     # unit tests only, no Docker needed
./mvnw verify   # everything, incl. Testcontainers integration tests; coverage in target/site/jacoco
```

## Future improvements

Out of scope for this assignment, with the code already structured to absorb them:

- **Authentication and authorization.** The caller identity (`userId`) is currently passed in the
  request; services take it as a parameter and never read it from HTTP, so only the controllers
  would change to take it from a JWT `sub` claim (e.g. Keycloak + Spring Security resource server).
- **Fleet management** (car CRUD and decommissioning) behind the existing `CarLookup` seam, which
  already holds the car row lock during a booking.
