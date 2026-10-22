#!/usr/bin/env bash
# End-to-end smoke test against a running instance (e.g. `docker compose up -d --build`).
# Walks the main flow over HTTP: find an available car, book it, prove the overlap is rejected and the
# reservation is private, cancel it. Needs only bash and curl.
#
# Usage: scripts/smoke-test.sh [base-url]   (default http://localhost:8080, or $BASE_URL)
set -euo pipefail

BASE_URL="${1:-${BASE_URL:-http://localhost:8080}}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

pass() { printf '  ok    %s\n' "$1"; }
fail() { printf '  FAIL  %s\n' "$1" >&2; [[ -s "$TMP/body" ]] && cat "$TMP/body" >&2 && echo >&2; exit 1; }

# request METHOD PATH [JSON]: prints the status code; body in $TMP/body, headers in $TMP/headers
request() {
    local args=(-s -o "$TMP/body" -D "$TMP/headers" -w '%{http_code}' -X "$1" "$BASE_URL$2")
    [[ $# -ge 3 ]] && args+=(-H 'Content-Type: application/json' -d "$3")
    curl "${args[@]}"
}

expect() { # expect STATUS DESCRIPTION METHOD PATH [JSON]
    local expected="$1" description="$2" status
    shift 2
    status="$(request "$@")" || fail "$description (no response)"
    [[ "$status" == "$expected" ]] && pass "$description" || fail "$description: expected $expected, got $status"
}

# first value of a string field anywhere in the last response body, e.g. json_field id
json_field() { grep -o "\"$1\":\"[^\"]*\"" "$TMP/body" | head -n 1 | cut -d '"' -f 4; }

uuid() { od -An -N16 -tx1 /dev/urandom | tr -d ' \n' | sed -E 's/^(.{8})(.{4}).(.{3}).(.{3})(.{12})$/\1-\2-4\3-a\4-\5/'; }

iso() { date -u -d "@$1" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -r "$1" +%Y-%m-%dT%H:%M:%SZ; } # GNU or BSD date

echo "Smoke test against $BASE_URL"

for _ in $(seq 1 60); do
    [[ "$(request GET /actuator/health/readiness 2>/dev/null)" == 200 ]] && break
    sleep 2
done
expect 200 "service is ready" GET /actuator/health/readiness

# a random 3-hour slot 30-60 days ahead, so reruns against the same database do not collide
now="$(date -u +%s)"
start=$(( (now / 3600 + 24 * 30 + RANDOM % (24 * 30)) * 3600 ))
from="$(iso "$start")"
to="$(iso $(( start + 3 * 3600 )))"
user="$(uuid)"

expect 200 "fleet listing" GET "/api/v1/cars?size=1"

expect 200 "availability for $from .. $to" GET "/api/v1/cars/availability?from=$from&to=$to&size=1"
car="$(json_field id)"
[[ -n "$car" ]] || fail "no available car returned"

booking="{\"carId\":\"$car\",\"userId\":\"$user\",\"startTime\":\"$from\",\"endTime\":\"$to\"}"
expect 201 "reserve car $car" POST /api/v1/reservations "$booking"
reservation="$(json_field id)"
grep -qi "^location: .*/api/v1/reservations/$reservation" "$TMP/headers" || fail "Location header points to the reservation"

expect 409 "overlapping reservation is rejected" POST /api/v1/reservations \
    "{\"carId\":\"$car\",\"userId\":\"$(uuid)\",\"startTime\":\"$from\",\"endTime\":\"$to\"}"
[[ "$(json_field code)" == RESERVATION_CONFLICT ]] || fail "conflict code is RESERVATION_CONFLICT"

expect 200 "availability no longer offers the car" GET "/api/v1/cars/availability?from=$from&to=$to&size=1"
[[ "$(json_field id)" != "$car" ]] || fail "booked car is still listed as available"

expect 200 "owner reads the reservation" GET "/api/v1/reservations/$reservation?userId=$user"
[[ "$(json_field status)" == CONFIRMED ]] || fail "status is CONFIRMED"
expect 404 "another user cannot see it" GET "/api/v1/reservations/$reservation?userId=$(uuid)"

expect 200 "owner cancels the reservation" POST "/api/v1/reservations/$reservation/cancel" "{\"userId\":\"$user\"}"
[[ "$(json_field status)" == CANCELLED ]] || fail "status is CANCELLED"

expect 422 "a 1-hour slot is rejected" POST /api/v1/reservations \
    "{\"carId\":\"$car\",\"userId\":\"$user\",\"startTime\":\"$from\",\"endTime\":\"$(iso $(( start + 3600 )))\"}"

expect 200 "Swagger UI is served" GET /swagger-ui/index.html
expect 200 "OpenAPI spec is served" GET /openapi/car-reservation-api.yaml

echo "Smoke test passed."
