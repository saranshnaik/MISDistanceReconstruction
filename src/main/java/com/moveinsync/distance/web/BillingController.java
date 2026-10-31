package com.moveinsync.distance.web;

import com.moveinsync.distance.security.TenantContext;
import com.moveinsync.distance.service.BillingService;
import com.moveinsync.distance.service.BillingSummary;
import com.moveinsync.distance.service.TripView;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/trips/{tripRef}")
public class BillingController {

    private final BillingService billing;
    private final TenantContext tenants;

    public BillingController(BillingService billing, TenantContext tenants) {
        this.billing = billing;
        this.tenants = tenants;
    }

    /** Close the invoice at the trip's current distance. */
    @PostMapping("/bill")
    public TripView bill(@PathVariable String tripRef) {
        return billing.bill(tenants.currentTenantId(), tripRef);
    }

    /** Late pings. After billing the effect shows up as a signed adjustment. */
    @PostMapping("/pings")
    public TripView latePings(@PathVariable String tripRef, @Valid @RequestBody LatePingsRequest request) {
        return billing.addLatePings(tenants.currentTenantId(), tripRef, request.toEngine());
    }

    @GetMapping("/adjustments")
    public BillingSummary adjustments(@PathVariable String tripRef) {
        return billing.summary(tenants.currentTenantId(), tripRef);
    }
}
