package com.moveinsync.distance.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BillingAdjustmentRepository extends JpaRepository<BillingAdjustmentEntity, Long> {

    List<BillingAdjustmentEntity> findByTripIdOrderByIdAsc(Long tripId);

    boolean existsByComputationId(Long computationId);
}
