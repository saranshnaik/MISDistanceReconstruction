package com.moveinsync.distance.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * No such trip for this tenant. Deliberately the same answer whether the trip does not exist
 * or belongs to someone else, so nobody can probe which trip refs exist.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class TripNotFoundException extends RuntimeException {

    public TripNotFoundException() {
        super("trip not found");
    }
}
