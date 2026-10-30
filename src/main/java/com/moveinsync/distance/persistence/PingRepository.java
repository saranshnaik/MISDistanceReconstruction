package com.moveinsync.distance.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PingRepository extends JpaRepository<PingEntity, Long> {

    List<PingEntity> findByTripIdOrderByRecordedAtAscIdAsc(Long tripId);

    long countByTripId(Long tripId);
}
