# Design note (initial)

Written before the code, as the brief asks. It is updated when the design changes;
the final version adds diagrams and a full failure table.

## Goal

Given the evidence for one trip (pings, milestones, planned stops, office), produce a
billable distance in km plus a line-by-line explanation, deterministically.

## Main components

| Component | Responsibility |
|---|---|
| `engine` (plain Java) | Cleaning, anchors, segment rules, routing policy. No Spring, no database, so it is unit-testable on its own. |
| `service` | Orchestration: load a trip, run the engine, store the result, handle billing and recompute. |
| `persistence` | JPA entities and repositories (trip, stops, pings, computations, billing adjustments). |
| `web` | REST controllers, DTOs, one global exception handler. |
| `security` | Login, roles (EMPLOYEE / OPERATOR / ADMIN), tenant taken from the token. |
| `observability` | Correlation id, structured logs, metrics. |

## Pipeline for one trip

```
pings + milestones
  -> clean pings (dedupe, sort, accuracy, teleport)
  -> add anchors (employee, no-show, office)
  -> merge into one ordered list of points
  -> per consecutive pair: short gap? parked? otherwise needs a route
  -> routing (cache, batch, circuit breaker) and validation
  -> segments with rule labels, total km (rounded once)
```

## Data model (tables carry a tenant id)

`trip`, `trip_stop`, `ping`, `distance_computation` (one row per computation, with input
hash and rule version), `billing_adjustment` (signed delta), `app_user`.

## APIs (planned)

- `POST /api/trips` ingest evidence and compute
- `POST /api/trips/{ref}/pings` late pings, recompute
- `GET /api/trips/{ref}` and `/segments` result and explanation
- `POST /api/trips/{ref}/bill` close the invoice
- `GET /api/trips/{ref}/adjustments` signed deltas

## Failure modes (initial list)

| What fails | What the user sees | Recovery |
|---|---|---|
| Routing provider down or slow | Trip marked DEGRADED, estimate = straight line x 1.4 | Circuit breaker; a scheduled job recomputes when the provider is back |
| Duplicate or out-of-order pings | Same result as a clean stream | Dedupe and sort before any distance is computed |
| Late pings after billing | A signed delta (e.g. +1.80 km) | Invoice is never edited; an adjustment row is added |
| Two recomputes at once | One result | Row lock on the trip |
| Bad GPS fix (teleport) | Distance unchanged | Dropped by the speed filter |
