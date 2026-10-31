package com.moveinsync.distance.persistence;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TripRepository extends JpaRepository<TripEntity, Long> {

    /** Anything that serves a user goes through the tenant id, never the bare trip ref. */
    Optional<TripEntity> findByTenantIdAndTripRef(String tenantId, String tripRef);

    /**
     * Same lookup with a row lock (SELECT ... FOR UPDATE) held until the transaction ends. Two
     * recomputes of one trip, even on different servers, queue up here instead of both writing.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TripEntity t where t.tenantId = :tenantId and t.tripRef = :tripRef")
    Optional<TripEntity> findForUpdate(@Param("tenantId") String tenantId, @Param("tripRef") String tripRef);

    /** For the recovery job: oldest degraded first. Not tenant-scoped on purpose. */
    List<TripEntity> findTop100ByDistanceStateOrderByIdAsc(DistanceState state);

    long countByDistanceState(DistanceState state);

    Optional<TripEntity> findFirstByDistanceStateOrderByIdAsc(DistanceState state);
}
