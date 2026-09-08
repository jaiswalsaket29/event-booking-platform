package org.saket.eventbooking.session;


import jakarta.persistence.*;
import lombok.*;
import org.saket.eventbooking.event.Event;
import org.saket.eventbooking.location.Hall;
import org.saket.eventbooking.location.Location;
import org.saket.eventbooking.session.enums.PricingMode;
import org.saket.eventbooking.session.enums.SeatingType;
import org.saket.eventbooking.session.enums.SessionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sessions")
@Getter
@Setter @NoArgsConstructor @AllArgsConstructor
public class Session {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id", nullable = false)
    private Location location;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hall_id")
    private Hall hall; // nullable — only for assigned seating

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(name = "end_time", nullable = false)
    private Instant endTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "seating_type", nullable = false)
    private SeatingType seatingType;

    @Enumerated(EnumType.STRING)
    @Column(name = "pricing_mode")
    private PricingMode pricingMode; // nullable — null for ASSIGNED_SEATING, required for GENERAL_ADMISSION

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SessionStatus status;

    @Column(name = "available_capacity")
    private Integer availableCapacity; // nullable — meaningful only when pricingMode = FLAT

    @Column(name = "base_price", nullable = false)
    private BigDecimal basePrice;
}