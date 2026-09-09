package org.saket.eventbooking.booking.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.saket.eventbooking.booking.enums.BookingStatus;
import org.saket.eventbooking.session.entity.Session;
import org.saket.eventbooking.session.entity.TicketTier;
import org.saket.eventbooking.user.entity.User;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "bookings")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private Session session;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_tier_id")
    private TicketTier ticketTier; // nullable — only set when session uses tiered pricing

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BookingStatus status;

    private Integer quantity; // GA/tiered bookings only, not assigned seating

    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount;

    @Column(name = "booking_reference", unique = true)
    private String bookingReference; // nullable — set only at PENDING -> CONFIRMED transition

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}