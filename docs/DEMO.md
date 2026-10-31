# Demo script

A talk-through for a short video (about 6 minutes): the main flows, one failure handled live, and
a few real API calls. Everything here was run against `docker compose up` on a clean database;
the output shown is real.

## Before recording

```
docker compose up --build        # first build is slow; wait until it settles
requests/demo.sh                 # in another terminal (it waits for the server by itself)
```

The server takes about 15 seconds to report `UP` on a fresh start: it creates the demo accounts
first, and `/actuator/health` says `OUT_OF_SERVICE` until that is done.

Have three things open: `docs/DESIGN.md` (the diagrams), `requests/demo.http` (or a terminal), and
`docs/benchmark-report.md`.

## What to say, in order

**1. The problem (30 s).** Vendors are paid per km. GPS drops out, repeats pings and sometimes
jumps. Adding up the pings under-pays; asking a maps API for every gap over-pays and costs calls.
Show the architecture diagram in DESIGN.md: plain-Java engine in the middle, routing behind a
cache, a circuit breaker, and a provider.

**2. Submit a trip with a 22-minute GPS hole (1 min).** `POST /api/trips` with
`requests/sample-trip.json`. Point at the answer:

```
state=OK  billing=UNBILLED  rebuilt=35.27 km  naive=30.00 km  gaps=2  provider calls=1  rules=v1-12625e44
```

How to read it honestly: the cab in this sample drives dead straight, so "naive" (a straight chain
through the pings) is exactly the true 30.00 km. The rebuilt figure is higher because, while the
pings are missing, the two gaps are priced by road, and the simulated roads are 25-45% longer than
the straight line. In a real city the straight chain is the under-count. The case-study version of
this, with a curved route, is `CaseStudyExamplesTest`: rebuilt about 31 km against a naive 27.x km.

**3. Every kilometre is explained (30 s).** `GET /api/trips/{ref}/segments`. Two segments are
`ROUTED` (the gaps) and 169 are `SHORT_GAP_AERIAL`. Open one segment and show its rule, durations
and `aerialMm` / `routedMm` / `billedMm`. Say: a vendor dispute is answered line by line.

**4. Sending it twice changes nothing (15 s).** Same `tripRef` again returns the same
`computationId`. Idempotent per tenant.

**5. Who may do what (45 s).** Real results from the run:

```
employee reading segments:        HTTP 403
operator closing an invoice:      HTTP 403
another tenant reading the trip:  HTTP 404   (looks exactly like a trip that does not exist)
no token at all:                  HTTP 401
```

The tenant comes from the signed token, never from the request.

**6. Billing, then late pings (1 min).** Admin closes the invoice: `billedKm=35.27`, frozen. Then
send `requests/late-pings.json` (the pings that fill the hole). Result:

```
billedKm=35.27  finalBilledKm=30.00
adjustments: "deltaKm":-5.27
```

Say: a closed invoice is never edited. The change is one signed adjustment; here it is negative
because real driving turned out shorter than the estimated road. (The positive case, where missing
pings arrive and the distance grows, is `BillingServiceTest`.)

**7. The failure, live (1.5 min).** Switch the provider off: `POST /api/admin/routing/outage` with
`{"down": true}`. Submit `requests/sample-trip-outage.json`:

```
state=DEGRADED  rebuilt=35.28 km  gaps=2  provider calls=0
trying to bill the estimate: HTTP 409
```

Say: the trip is answered immediately with straight line x 1.4 and clearly marked `DEGRADED`; it
cannot be billed. Switch the provider back on and recompute (the background job would do it within
30 seconds):

```
state=OK  rebuilt=35.12 km  billing it now: HTTP 200
```

Then open `ProviderFailsHalfwayTest`: the same thing at 10,000 trips with the provider dying at
trip 5,000; only 5 calls ever reach the dead provider (the circuit breaker), every trip is
answered, and every recomputed trip matches a no-outage run exactly.

**8. What on-call would look at (30 s).** `GET /actuator/prometheus` as admin:

```
distance_trips_degraded 0.0                       backlog of estimated trips
distance_trips_degraded_oldest_age_seconds 0.0    how long the oldest has waited
distance_routing_calls_total 2.0                  money spent on the provider
distance_routing_circuit_state 0.0                0 closed, 1 testing, 2 open
distance_billing_adjustments_total{direction="negative"} 1.0
```

**9. Scale and tests (45 s).** Show `docs/benchmark-report.md`: 10,000 trips of about 1,500 pings
each, provider calls cut from 46,741 to 108 by the cache and batching, and the 1-thread and
16-thread runs giving the same total to the millimetre. Then `./mvnw test`: 241 tests, including
the concurrency tests and the failure-injection test.

**10. Close (30 s).** The trade-offs in one breath: money consistency over availability (estimates
are shown but never billed); cell-centre routing trades up to about 150 m of position error for a
cache that can never change a bill; one row lock per trip trades throughput for safety. And what
is not there: a real maps provider, per-call timeouts and retries, shared cache across instances
(all listed in DESIGN.md).

## Full output of one run

```
== 1. submit a trip with a 22-minute GPS hole and a drop-off inside it
   state=OK  billing=UNBILLED  rebuilt=35.27 km  naive=30.00 km  gaps=2  provider calls=1  rules=v1-12625e44
== 2. submit the same trip again: nothing changes
   computation id first=1, after resubmit=1
== 3. why is every kilometre there? (segments by the rule that priced them)
         2 "rule":"ROUTED"
       169 "rule":"SHORT_GAP_AERIAL"
== 4. who may do what
   employee reading segments:        HTTP 403   (403 expected)
   operator closing an invoice:      HTTP 403   (403 expected)
   another tenant reading the trip:  HTTP 404   (404 expected)
   no token at all:                  HTTP 401   (401 expected)
== 5. admin closes the invoice
   billing=BILLED  billedKm=35.27 (frozen from now on)
== 6. late pings fill the hole after billing: a signed adjustment, the invoice is not edited
   billedKm=35.27  finalBilledKm=30.00
   adjustments: "deltaKm":-5.27
== 7. FAILURE: the routing provider goes down
   state=DEGRADED  billing=UNBILLED  rebuilt=35.28 km  gaps=2  provider calls=0
   trying to bill the estimate:       HTTP 409   (409 expected)
== 8. RECOVERY: provider back, trip recomputed, now it can be billed
   state=OK  billing=UNBILLED  rebuilt=35.12 km  gaps=2  provider calls=1
   billing it now:                    HTTP 200   (200 expected)
```

The exact kilometres for the recovered trip can differ by a little between runs because each run
uses a fresh stretch of road, and the simulated provider's detour factor (1.25-1.45) is derived
from the coordinates.
