package org.saket.eventbooking.booking.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.saket.eventbooking.session.entity.SessionSeat;

import java.util.UUID;

@Entity
@Table(
        name = "booking_seats",
        uniqueConstraints = @UniqueConstraint(columnNames = {"booking_id", "session_seat_id"})
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class BookingSeat {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_seat_id", nullable = false)
    private SessionSeat sessionSeat;
}