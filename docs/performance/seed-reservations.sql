-- Load-test data for the availability query: 50 reservations per car (500,000 rows) spread over
-- 2030, one per week, so no two overlap; about 10 % are cancelled. Run against the compose database:
--   docker compose exec -T postgres psql -U car_reservation -d car_reservation < docs/performance/seed-reservations.sql
TRUNCATE reservation;

INSERT INTO reservation (id, car_id, user_id, start_time, end_time, status, version, created_at)
SELECT uuidv7(),
       c.id,
       gen_random_uuid(),
       s.start_time,
       s.start_time + make_interval(hours => 2 + (abs(hashtext(c.id::text || w)) % 23)),
       CASE WHEN abs(hashtext(c.license_plate || w)) % 10 = 0 THEN 'CANCELLED' ELSE 'CONFIRMED' END,
       0,
       now()
FROM car c
CROSS JOIN generate_series(0, 49) AS w
CROSS JOIN LATERAL (
    SELECT timestamptz '2030-01-01 00:00:00+00' + make_interval(days => w * 7, hours => abs(hashtext(c.id::text)) % 100)
) AS s(start_time);

ANALYZE car;
ANALYZE reservation;
