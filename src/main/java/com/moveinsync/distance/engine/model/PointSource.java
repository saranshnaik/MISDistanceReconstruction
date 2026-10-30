package com.moveinsync.distance.engine.model;

/**
 * Where a point on the track came from. It ends up on every segment in the output, so when a
 * vendor asks "why does this km exist" we can say which point it was measured from.
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
