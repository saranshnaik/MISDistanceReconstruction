package com.moveinsync.distance.service;

/**
 * No trustworthy distance yet (never computed, or only an estimate because routing was
 * down), so it can't go on an invoice.
 */
public class TripNotBillableException extends RuntimeException {

    public TripNotBillableException() {
        super("trip cannot be billed yet");
    }
}
