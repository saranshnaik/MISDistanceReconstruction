package com.moveinsync.distance.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * The trip has no trustworthy distance yet (never computed, or only an estimate because
 * routing was down), so it must not be put on an invoice.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class TripNotBillableException extends RuntimeException {

    public TripNotBillableException() {
        super("trip cannot be billed yet");
    }
}
