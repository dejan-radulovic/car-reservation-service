# Availability query performance

`GET /api/v1/cars/availability` returns cars without a CONFIRMED reservation overlapping
`[from, to)`, ordered by license plate, one page at a time:

```sql
SELECT c.*
FROM car c
WHERE NOT EXISTS (
    SELECT 1 FROM reservation r
    WHERE r.car_id = c.id
      AND r.status = 'CONFIRMED'
      AND r.period && tstzrange(:from, :to, '[)')
)
ORDER BY c.license_plate
OFFSET :offset LIMIT :size + 1   -- one extra row tells whether there is a next page
```

No index was added for it. The query reuses two indexes that already exist for integrity:

- `uq_car_license_plate` (unique B-tree), which provides the ordering;
- the GiST index behind the exclusion constraint `ex_reservation_no_overlap`, i.e.
  `(car_id, period) WHERE status = 'CONFIRMED'`, which answers "does this car have an overlapping
  confirmed reservation?" with one index probe. The predicate `status = 'CONFIRMED'` matches the
  partial index, and `period` is the same half-open `tstzrange` the constraint uses, so search and
  booking can never disagree about what overlapping means.

## Measurements

Environment: PostgreSQL 18.6 in Docker (`compose.yaml`) on a developer laptop, with the 10,000
seeded cars and **500,000 reservations** (50 per car across 2030, 10 % cancelled), loaded with
[`seed-reservations.sql`](seed-reservations.sql). The window is 24 h on 2030-03-06, in which
3,176 cars are booked and 6,824 are available. Page size is 50.

### First page: 1.2 ms

```
Limit (actual time=0.520..1.177 rows=51 loops=1)
  ->  Nested Loop Anti Join (rows=51)
        ->  Index Scan using uq_car_license_plate on car c (rows=77)
        ->  Index Only Scan using ex_reservation_no_overlap on reservation r (rows=0.34 loops=77)
              Index Cond: ((car_id = c.id) AND (period && '["2030-03-06 00:00:00+00","2030-03-07 00:00:00+00")'::tstzrange))
Execution Time: 1.228 ms
```

Cars are read in license plate order and each one is probed once in the GiST index. The scan stops
after 51 available cars (77 cars read, 26 of them booked). The cost depends on the page size, not on
the fleet or reservation count.

### Deep page (offset 2,500): 52 ms

```
Limit (actual time=51.002..51.009 rows=51 loops=1)
  ->  Sort (top-N heapsort)  Sort Key: c.license_plate
        ->  Hash Right Anti Join (rows=6824)
              ->  Bitmap Heap Scan on reservation r (rows=3176)
                    ->  Bitmap Index Scan on ex_reservation_no_overlap
                          Index Cond: (period && '[...)'::tstzrange)
              ->  Hash  ->  Seq Scan on car c (rows=10000)
Execution Time: 51.594 ms
```

For deep offsets the planner switches strategy. It finds all overlapping reservations in one GiST
scan, anti-joins them against all cars in memory, and sorts. That is still fast at this scale.

## Possible improvements (not needed at 10,000 cars)

- **Keyset pagination** (`WHERE c.license_plate > :lastPlate`) instead of `OFFSET` would keep every
  page on the 1 ms plan.
- A **separate GiST index on `period WHERE status = 'CONFIRMED'`** would speed up the deep-page
  strategy, whose GiST scan currently cannot use the leading `car_id` column.

## Reproducing

```bash
docker compose up -d && ./mvnw spring-boot:run   # applies the schema and the car seed; stop it afterwards
docker compose exec -T postgres psql -U car_reservation -d car_reservation < docs/performance/seed-reservations.sql
docker compose exec -T postgres psql -U car_reservation -d car_reservation \
  -c "EXPLAIN (ANALYZE, BUFFERS) SELECT ... "      # the query above with literal values
```
