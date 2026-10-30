package com.moveinsync.distance.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DistanceComputationRepository extends JpaRepository<DistanceComputationEntity, Long> {

    Optional<DistanceComputationEntity> findFirstByTripIdOrderByIdDesc(Long tripId);

    List<DistanceComputationEntity> findByTripIdOrderByIdAsc(Long tripId);
}
