package com.moveinsync.distance.engine.cleaning;

import com.moveinsync.distance.engine.model.Ping;
import java.util.List;

/** Surviving pings (oldest first) and the report of what got dropped. */
public record CleanedPings(List<Ping> pings, CleaningReport report) {
}
