package com.moveinsync.distance.service;

/**
 * No such trip for this tenant. Deliberately the same answer whether the trip does not exist
 * or belongs to someone else, so nobody can probe which trip refs exist.
 */
public class TripNotFoundException extends RuntimeException {

    public TripNotFoundException() {
        super("trip not found");
    }
}
