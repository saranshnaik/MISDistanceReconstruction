package com.moveinsync.distance.persistence;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TripRepository extends JpaRepository<TripEntity, Long> {

    /** Every lookup that serves a user goes through the tenant id, never the bare trip ref. */
    Optional<TripEntity> findByTenantIdAndTripRef(String tenantId, String tripRef);

    /**
     * Same lookup but takes a row lock (SELECT ... FOR UPDATE) until the transaction ends.
     * Two recomputes of the same trip, even on different servers, queue up here instead of
     * both writing a result.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TripEntity t where t.tenantId = :tenantId and t.tripRef = :tripRef")
    Optional<TripEntity> findForUpdate(@Param("tenantId") String tenantId, @Param("tripRef") String tripRef);

    /** For the recovery job: oldest degraded trips first. Not tenant-scoped on purpose. */
    List<TripEntity> findTop100ByDistanceStateOrderByIdAsc(DistanceState state);

    long countByDistanceState(DistanceState state);

    Optional<TripEntity> findFirstByDistanceStateOrderByIdAsc(DistanceState state);
}
