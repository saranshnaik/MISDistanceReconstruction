package com.moveinsync.distance.web;

import com.moveinsync.distance.security.Role;
import com.moveinsync.distance.security.TenantContext;
import com.moveinsync.distance.service.SegmentView;
import com.moveinsync.distance.service.TripService;
import com.moveinsync.distance.service.TripView;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Thin on purpose: translate HTTP into service calls. The rules live in the engine. */
@RestController
@RequestMapping("/api/trips")
public class TripController {

    private final TripService trips;
    private final TenantContext tenants;

    public TripController(TripService trips, TenantContext tenants) {
        this.trips = trips;
        this.tenants = tenants;
    }

    /** Send a trip's evidence. Sending the same tripRef again returns the existing result. */
    @PostMapping
    public TripView submit(@Valid @RequestBody TripRequest request) {
        return trips.ingest(tenants.currentTenantId(), request.tripRef(), request.toEngine());
    }

    @GetMapping("/{tripRef}")
    public TripView get(@PathVariable String tripRef) {
        if (tenants.currentRole() == Role.EMPLOYEE) {
            return trips.getForEmployee(tenants.currentTenantId(), tripRef, tenants.currentEmployeeRef());
        }
        return trips.get(tenants.currentTenantId(), tripRef);
    }

    /** The line-by-line explanation: every segment with the rule that produced its distance. */
    @GetMapping("/{tripRef}/segments")
    public List<SegmentView> segments(@PathVariable String tripRef) {
        return trips.segments(tenants.currentTenantId(), tripRef);
    }

    @PostMapping("/{tripRef}/recompute")
    public TripView recompute(@PathVariable String tripRef) {
        return trips.compute(tenants.currentTenantId(), tripRef);
    }
}
