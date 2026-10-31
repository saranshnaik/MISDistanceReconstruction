package com.moveinsync.distance.web;

import com.moveinsync.distance.engine.model.Ping;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Body of POST /api/trips/{tripRef}/pings. */
public record LatePingsRequest(@NotEmpty @Size(max = 50_000) List<@NotNull @Valid PingDto> pings) {

    public List<Ping> toEngine() {
        return pings.stream().map(PingDto::toEngine).toList();
    }
}
