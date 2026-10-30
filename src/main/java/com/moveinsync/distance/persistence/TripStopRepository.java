package com.moveinsync.distance.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TripStopRepository extends JpaRepository<TripStopEntity, Long> {

    List<TripStopEntity> findByTripIdOrderByIdAsc(Long tripId);

    boolean existsByTripIdAndEmployeeRef(Long tripId, String employeeRef);
}
