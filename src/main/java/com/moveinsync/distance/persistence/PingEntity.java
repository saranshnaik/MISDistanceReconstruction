package com.moveinsync.distance.persistence;

import com.moveinsync.distance.engine.model.GeoPoint;
import com.moveinsync.distance.engine.model.Ping;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** One raw GPS reading. receivedAt is when WE got it, which is how late pings are spotted. */
@Entity
@Table(name = "ping")
public class PingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String tenantId;
    private Long tripId;
    private Instant recordedAt;
    private double lat;
    private double lon;
    // explicit name: the default snake_case rule would turn a trailing capital M into "accuracym"
    @Column(name = "accuracy_m")
    private double accuracyM;
    private Instant receivedAt;

    protected PingEntity() {
        // for JPA
    }

    public PingEntity(String tenantId, Long tripId, Ping ping, Instant receivedAt) {
        this.tenantId = tenantId;
        this.tripId = tripId;
        this.recordedAt = ping.time();
        this.lat = ping.point().lat();
        this.lon = ping.point().lon();
        this.accuracyM = ping.accuracyM();
        this.receivedAt = receivedAt;
    }

    public Ping toEngine() {
        return new Ping(recordedAt, new GeoPoint(lat, lon), accuracyM);
    }

    public Long getId() {
        return id;
    }

    public Long getTripId() {
        return tripId;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
