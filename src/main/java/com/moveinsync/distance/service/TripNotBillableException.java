package com.moveinsync.distance.service;

/**
 * The trip has no trustworthy distance yet (never computed, or only an estimate because
 * routing was down), so it must not be put on an invoice.
 */
public class TripNotBillableException extends RuntimeException {

    public TripNotBillableException() {
        super("trip cannot be billed yet");
    }
}
