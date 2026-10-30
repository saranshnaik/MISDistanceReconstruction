# GPS gap backfill and billable distance

My take on MoveInSync case study 1.

Vendors get paid per kilometre, and the kilometres come from a GPS box in the cab.
That box goes quiet in tunnels and basements, sometimes dies, and sometimes sends the
same ping twice or one from 9 km away. If you just add up the distance between pings,
the vendor gets short-changed. If you ask a maps API to fill every hole, you overpay
and the API bill explodes.

This service rebuilds a distance for each trip, and every segment in the answer says
which rule produced it (short gap, parked, routed, rejected...). That way a dispute can be
settled line by line instead of "the system said so".

Java 21 / Spring Boot 4, PostgreSQL, Flyway. The distance logic itself is plain Java with no
Spring in it. The design, with diagrams and a failure table, is in [docs/DESIGN.md](docs/DESIGN.md).

## Run it

```
docker compose up --build
```

That starts the app and Postgres (Flyway creates the tables on first start). The first build is
slow because it downloads the Java images and Maven dependencies.

- health: http://localhost:8080/actuator/health
- port 8080 busy? `APP_PORT=18080 docker compose up --build`
- before real use: set `JWT_SECRET` (32+ characters) and `SEED_DEMO_USERS=false`

Tests need no Docker (they use an in-memory H2 database):

```
./mvnw test                  # the normal suite (241 tests)
./mvnw test -Pbenchmark      # also the 10,000-trip benchmark and the 10,000-trip failure test
```

You need JDK 21 or newer. Ready-made requests are in [requests/demo.http](requests/demo.http).

### Try it by hand

Demo accounts exist for two tenants, `acme` and `globex`, each with `-admin`, `-operator` and
`-employee`, all with the password `demo-pass` (demo only, see below).

```
# 1. log in
curl -s -X POST localhost:8080/api/auth/token -H 'Content-Type: application/json' \
     -d '{"username":"acme-operator","password":"demo-pass"}'
# 2. send a trip (use the accessToken from step 1)
curl -s -X POST localhost:8080/api/trips -H "Authorization: Bearer $TOKEN" \
     -H 'Content-Type: application/json' -d @requests/sample-trip.json
# 3. see why every kilometre is there
curl -s localhost:8080/api/trips/DEMO-1/segments -H "Authorization: Bearer $TOKEN"
```

## API

| Endpoint | Who | What |
|---|---|---|
| `POST /api/auth/token` | anyone | username + password in, signed token out |
| `POST /api/trips` | operator, admin | send a trip's evidence; sending the same `tripRef` again returns the first result |
| `GET /api/trips/{ref}` | employee (own trips only), operator, admin | state and distance |
| `GET /api/trips/{ref}/segments` | operator, admin | every segment with the rule that priced it |
| `POST /api/trips/{ref}/recompute` | operator, admin | recompute (does nothing if input and rules are unchanged) |
| `POST /api/trips/{ref}/pings` | operator, admin | pings that arrived late |
| `POST /api/trips/{ref}/bill` | admin | close the invoice; refused (409) while the trip is only an estimate |
| `GET /api/trips/{ref}/adjustments` | admin | signed corrections to the closed invoice |
| `GET/POST /api/admin/routing[/outage]` | admin | stage a routing outage for the demo |
| `GET /actuator/prometheus`, `/actuator/metrics` | admin | metrics |
| `GET /actuator/health` | anyone | health |

The tenant always comes from the signed token, never from the request. Another tenant's trip
looks exactly like a trip that does not exist (404). Anything not listed above is denied.

## How a trip is priced

Rules run in this fixed order and the order is part of the rule version.

| Step | Rule | Label |
|---|---|---|
| clean | drop pings outside the trip, with bad coordinates or accuracy over 50 m; sort; drop same-timestamp duplicates; drop teleports above 120 km/h | counted by reason |
| anchors | employee pickup/drop, else home + sign-in time (ad-hoc) or planned spot + time (planned); no-shows behind a flag; office point | `EMPLOYEE_*`, `NO_SHOW_*`, `OFFICE_*` |
| segment | under 70 s at a believable speed: straight line | `SHORT_GAP_AERIAL` |
| segment | 70 s or more below 5 km/h: straight line | `PARKED` |
| segment | anything else: ask for the driving distance | `ROUTED` |
| check | routed over 100 km | `REJECTED_DISTANCE` (billed 0) |
| check | routed speed AND 1.4 x straight-line speed both over the limit | `REJECTED_SPEED` (billed 0) |
| outage | routing unavailable: straight line x 1.4, trip marked `DEGRADED` | `DEGRADED_ESTIMATE` |

Every threshold lives in one place (`RuleConfig`). The rule version string is `v1-` plus a hash of
all of them, so changing a number changes the version even if nobody remembers to bump anything.

## Rounding and units

- Each segment's distance is rounded **once**, to whole millimetres, and kept as a `long`.
- A trip's total is a plain `long` sum, so the order of adding can never change it.
- Kilometres are made **once, at the end**: millimetres / 1,000,000 as an exact decimal, rounded
  half-up to 2 places. Signed adjustments are exact decimal differences.
- The haversine uses `StrictMath`, which gives the same bits on every machine and JVM. Two runs
  that disagree by 0.1 km would be a billing bug, so this is not left to chance.

## Assumptions I had to make

The case study leaves these open. Each choice is covered by a test.

1. **Accuracy limit 50 m** (configurable). A missing or negative accuracy counts as untrustworthy.
2. **Duplicate = same timestamp.** The most accurate one wins, with latitude/longitude as the tie
   break, so the result does not depend on the order pings arrived in.
3. **Only pings inside the trip window count**; we bill the trip itself.
4. **Teleport check** compares each ping with the last *accepted* one. The first ping must be
   confirmed by the second. Known limitation: two or more consistent bad fixes at the very start
   cannot be told apart from the real start.
5. **An anchor is usable** if its point is a real coordinate (not out of range, not 0,0) and its
   time falls inside the trip window. Ad-hoc riders never use planned fields; planned riders never
   use home fields. I assume an ad-hoc rider's sign-in happens during the trip.
6. **Office:** on a login trip it is the trip end, on a logout trip the trip start (a mirror of
   the case study's wording). The device's own point is used only within 400 m of the planned
   office (400 m itself counts).
7. **Rejected segments bill 0** but stay in the output with their numbers. If even a generous
   detour cannot explain the speed, one of the two points is wrong.
8. **A trip with no usable pings** bills 0 km (only the office anchor remains).
9. **Routes are asked between grid-cell centres** (cells are about 110 m) so a cache hit and a
   miss give identical numbers. Two points in the same cell get the straight line and no call.
   Cost: up to about 150 m of position error on a routed gap.
10. **"Naive km"** in the output is the straight lines between consecutive cleaned pings, so the
    effect of the gap filling is visible next to the rebuilt number.
11. **A `DEGRADED` trip can be read but not billed.** An estimate never touches an invoice.
12. **Home coordinates never appear in output.** Segments whose point came from a rider's home
    address carry the source but no coordinates.
13. **Demo accounts** are created at start-up for trying the API. They are not real accounts.

## Benchmark

10,000 synthetic trips, about 1,500 pings each (1,523 on average), generated from a seed with 40
shared routes, GPS blackout zones with drop-offs inside them, duplicates, bad-accuracy fixes, 9 km
bursts, pings from before the trip, and shuffled order. Full report: [docs/benchmark-report.md](docs/benchmark-report.md).
Rerun with `./mvnw test -Pbenchmark`.

Machine: Windows 11, AuthenticAMD (AMD64 Family 25 Model 117 Stepping 2), 16 logical cores, 15.7 GB RAM,
Java 27 (HotSpot), 3.9 GB max heap.

| scenario | wall time | trips/s | engine p50 | engine p99 | peak heap |
|---|---|---|---|---|---|
| cache off, 1 thread | 9.9 s | 1,012 | 0.71 ms | 2.01 ms | 422 MB |
| cache on, 1 thread | 12.6 s | 797 | 0.97 ms | 2.36 ms | 423 MB |
| cache on, 16 threads | 1.5 s | 6,892 | 1.52 ms | 8.43 ms | 417 MB |

| | gaps needing a route | provider calls |
|---|---|---|
| before caching (one call per gap) | 46,741 | 46,741 |
| after caching and batching (25 per call) | 46,741 | 108 (99.8% fewer) |

- Total distance across all 10,000 trips is identical, to the millimetre, between the 1-thread and
  16-thread runs.
- With the cache on, one thread is slightly *slower* than with it off: the cell bookkeeping costs
  CPU. The cache saves provider calls, not compute.
- The routing provider here answers instantly. At an assumed 30 ms per real call, 46,741 calls would
  mean about 23 minutes of waiting and 108 calls about 3 seconds. That is arithmetic, not a measurement.
- Peak heap is sampled every 25 ms and includes garbage not yet collected.
- Latency is the engine only (generating the trip is not timed). Wall time includes generation.
- It stops scaling where DESIGN.md says: the database and the connection pool first, not the engine.

## Caching: what can go stale

Route distances are cached by (start cell, end cell) for up to **24 hours**, up to 100,000 entries.
A road change can therefore take up to a day to show up. It cannot silently change a bill: an
unchanged trip with an `OK` result is never recomputed, and when something does change, the
difference is booked as an explicit signed adjustment. Details in DESIGN.md.

## How the hard rules are tested

| Rule from the case study | Where it is tested |
|---|---|
| dedupe, order, accuracy, trip window | `PingCleanerTest` |
| teleports above the limit (configurable), the 9 km / 4 s burst | `TeleportFilterTest`, `CaseStudyExamplesTest` |
| employee anchor fallback order, valid/invalid points | `EmployeeAnchorTest` |
| no-show anchors behind a flag | `NoShowAnchorTest` |
| office anchor within 400 m (399 m vs 401 m) | `OfficeAnchorTest` |
| 70 s and 5 km/h boundaries | `AerialRulesTest` |
| 100 km rejection, speed rejection with the 1.4 factor | `RoutedDistancePolicyTest` |
| every segment carries its rule; the 22-minute underpass example | `DistanceEngineTest`, `CaseStudyExamplesTest` |
| cache by cell, batching, calls per trip, before and after | `CachingRouteResolverTest`, `DistanceEngineTest`, `BenchmarkTest` |
| circuit breaker, DEGRADED, automatic recompute | `CircuitBreakerTest`, `CircuitBreakingRouteResolverTest`, `DegradedModeTest`, `DegradedTripRecomputeJobTest` |
| same input and rules, same kilometres (shuffled, repeated, parallel) | `DistanceEngineTest`, `MillimetresTest`, `HaversineTest`, `BenchmarkTest` |
| late pings after billing give a signed delta; the invoice is not edited | `BillingServiceTest` |
| **failure injection**: provider dies halfway through 10,000 trips | `ProviderFailsHalfwayTest` |
| **concurrency**: 16 threads on one cache entry; 20 threads for one breaker probe; 8 concurrent ingests; 12 concurrent late-ping batches | `CachingRouteResolverTest`, `CircuitBreakerTest`, `TripServiceTest`, `BillingServiceTest` |
| login, forged/expired tokens, roles, tenant isolation | `AuthenticationTest`, `AccessControlTest` |
| error bodies without personal data (response and log) | `ErrorResponsesTest`, `UnexpectedErrorTest` |
| trace ids, JSON logs, metrics | `RequestTraceFilterTest`, `MetricsTest` |
| schema and constraints | `PersistenceTest` |

## Trade-offs, short version

Money consistency over availability (an estimate is shown but cannot be billed); latency and cost
over exactness on routes (cell centres, up to ~150 m); one row lock per trip over throughput; a
synchronous API over buffering. The long version, including what the system does *not* do, is in
[docs/DESIGN.md](docs/DESIGN.md).

## What I left out

A real maps provider (a simulated one sits behind the same interface), per-call timeouts and
retries on the provider, a message broker, a shared cache and circuit breaker across instances,
per-tenant rule configuration, re-billing when the rule version changes, refresh tokens and user
management, login rate limiting, invoice documents, and a UI.

## Layout

```
engine/       plain Java: cleaning, anchors, rules, routing (cache, batching, circuit breaker)
service/      stores trips, locks, hashes, calls the engine, billing, recovery job
persistence/  JPA entities and repositories (Flyway migrations in resources/db/migration)
web/          controllers, request validation, one error handler
security/     tokens, roles, tenant from the token
observability trace ids and metrics
```
