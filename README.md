# GPS gap backfill and billable distance

My take on MoveInSync case study 1.

Vendors get paid per kilometre, and the kilometres come from a GPS box in the cab.
That box goes quiet in tunnels and basements, sometimes dies, and sometimes sends the
same ping twice or one from 9 km away. If you just add up the distance between pings,
the vendor gets short-changed. If you ask a maps API to fill every hole, you overpay
and the API bill explodes.

This service rebuilds a distance for each trip, and every segment in the answer says
which rule produced it (short gap, parked, routed, rejected...). That way a dispute can
be settled line by line instead of "the system said so".

**Where it stands:** being built in phases, one commit at a time. The engine (cleaning,
anchors, rules, routing with cache/batching/circuit breaker), the database, the API, billing
with adjustments and the recovery job are in. Login and roles, error handling, metrics, the
benchmark and the final write-up come next.
The design is written up in [docs/DESIGN.md](docs/DESIGN.md).

## Running it

```
docker compose up --build
```

This starts the app and a Postgres database together (the app waits for Postgres to be
healthy, and Flyway creates the tables on first start). Then:

- health check: http://localhost:8080/actuator/health
- port 8080 busy? `APP_PORT=18080 docker compose up --build`

The first build is slow because it downloads the Java images and the Maven dependencies.

Without Docker you need JDK 21 or newer. The tests use an in-memory H2 database, so no
Postgres is needed:

```
./mvnw test
```

### Trying it by hand

Submit a trip (the response has the distance, its state and how many routing calls it cost):

```
curl -X POST localhost:8080/api/trips -H 'Content-Type: application/json' -d @trip.json
```

Then read the line-by-line explanation, close the invoice, and send late pings:

```
curl localhost:8080/api/trips/<tripRef>/segments
curl -X POST localhost:8080/api/trips/<tripRef>/bill
curl -X POST localhost:8080/api/trips/<tripRef>/pings -H 'Content-Type: application/json' -d @late.json
curl localhost:8080/api/trips/<tripRef>/adjustments
```

After billing, late pings never change the invoice. They add a signed adjustment (for
example `+1.80`), and `finalBilledKm` is the invoice plus all adjustments.

## How one trip gets processed

1. Clean the pings: dedupe, sort, drop low accuracy, drop teleports.
2. Add anchor points: where employees were picked up or dropped, no-shows, the office.
3. For every pair of neighbouring points pick a rule and work out the distance.
4. Add the segments up, round once at the very end, return everything with its labels.

## Still to write

Assumptions, rounding policy, benchmark numbers, trade-offs. They go in as the
matching code lands, so they describe what was actually built.
