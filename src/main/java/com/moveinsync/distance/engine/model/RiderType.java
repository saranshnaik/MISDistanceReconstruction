package com.moveinsync.distance.engine.model;

/**
 * AD_HOC riders book the cab on the spot, so all we know up front is their home and sign-in
 * time. PLANNED riders are on the roster, so we know a planned location and a planned time.
 */
public enum RiderType {
    AD_HOC,
    PLANNED
}
