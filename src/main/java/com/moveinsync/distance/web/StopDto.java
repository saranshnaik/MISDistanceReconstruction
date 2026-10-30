package com.moveinsync.distance.web;

import com.moveinsync.distance.engine.model.EmployeeStop;
import com.moveinsync.distance.engine.model.RiderType;
import com.moveinsync.distance.engine.model.StopEvent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/** An employee's pickup / drop / no-show. Locations and times are all optional. */
public record StopDto(
        @NotBlank @Size(max = 128) String employeeRef,
        @NotNull RiderType riderType,
        @NotNull StopEvent event,
        @Valid PointDto actual,
        Instant actualTime,
        @Valid PointDto planned,
        Instant plannedTime,
        @Valid PointDto home,
        Instant signInTime) {

    EmployeeStop toEngine() {
        return new EmployeeStop(employeeRef, riderType, event,
                PointDto.toEngine(actual), actualTime,
                PointDto.toEngine(planned), plannedTime,
                PointDto.toEngine(home), signInTime);
    }
}
