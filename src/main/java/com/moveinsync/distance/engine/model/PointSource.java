package com.moveinsync.distance.engine.model;

/**
 * Where a track point came from. It is on every output segment, so "why does this km
 * exist" can be answered.
 */
public enum PointSource {
    GPS_PING,
    EMPLOYEE_ACTUAL,
    EMPLOYEE_HOME_SIGNIN,
    EMPLOYEE_PLANNED,
    NO_SHOW_ACTUAL,
    NO_SHOW_PLANNED,
    OFFICE_ACTUAL,
    OFFICE_PLANNED
}
