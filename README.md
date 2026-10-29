# GPS Gap Backfill & Billable Distance Reconstruction

Backend case study for MoveInSync (Java / Spring Boot). Vendors are paid per
kilometre, but GPS pings go missing, arrive twice or jump across the city. This
service rebuilds a distance for every trip that MoveInSync and the vendor can
both trust, and labels every segment with the rule that produced it.

> Work in progress. The design is in [docs/DESIGN.md](docs/DESIGN.md).
> Sections marked _TODO_ are filled in as each phase lands.

## Run it

```
docker compose up --build
```

The app listens on http://localhost:8080 (health: `/actuator/health`).

To build and test without Docker you need JDK 21 or newer:

```
./mvnw test
```

## How a trip is processed

1. Clean the pings (duplicates, order, low accuracy, teleports).
2. Add anchor points (employee pickups/drops, no-shows, office).
3. For each pair of consecutive points choose a rule and a distance.
4. Sum the segments, round once, and return the result with every segment labelled.

## Assumptions, rounding, benchmark, trade-offs

_TODO: filled in during later phases._
