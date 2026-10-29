package com.moveinsync.distance.engine.cleaning;

import com.moveinsync.distance.engine.model.Ping;
import java.util.List;

/** Pings that survived cleaning (oldest first) plus the report of what was removed. */
public record CleanedPings(List<Ping> pings, CleaningReport report) {
}
