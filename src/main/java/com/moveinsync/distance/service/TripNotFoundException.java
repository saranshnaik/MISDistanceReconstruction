package com.moveinsync.distance.service;

/**
 * No such trip for this tenant. Same answer whether it doesn't exist or belongs to someone
 * else, so nobody can probe which trip refs exist.
 */
public class TripNotFoundException extends RuntimeException {

    public TripNotFoundException() {
        super("trip not found");
    }
}
