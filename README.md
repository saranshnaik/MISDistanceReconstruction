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

**Where it stands:** being built in phases, one commit at a time. Ping cleaning is done.
Anchors, segment rules, routing, persistence and the API come next.
The design is written up in [docs/DESIGN.md](docs/DESIGN.md).

## Running it

```
docker compose up --build
```

Then open http://localhost:8080/actuator/health. The first build is slow because it
downloads the Java images and the Maven dependencies.

Without Docker you need JDK 21 or newer:

```
./mvnw test
```

## How one trip gets processed

1. Clean the pings: dedupe, sort, drop low accuracy, drop teleports.
2. Add anchor points: where employees were picked up or dropped, no-shows, the office.
3. For every pair of neighbouring points pick a rule and work out the distance.
4. Add the segments up, round once at the very end, return everything with its labels.

## Still to write

Assumptions, rounding policy, benchmark numbers, trade-offs. They go in as the
matching code lands, so they describe what was actually built.
