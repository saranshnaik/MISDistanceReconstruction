package com.moveinsync.distance.persistence;

/** Where a trip is in its distance life: not computed yet, computed, or only estimated. */
public enum DistanceState {
    PENDING,
    OK,
    DEGRADED
}
