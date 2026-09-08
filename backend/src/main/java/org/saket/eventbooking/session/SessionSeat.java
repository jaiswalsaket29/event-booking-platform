package org.saket.eventbooking.session;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.saket.eventbooking.location.Seat;
import org.saket.eventbooking.session.enums.SessionSeatStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "session_seats",
        uniqueConstraints = @UniqueConstraint(columnNames = {"session_id", "seat_id"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SessionSeat {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private Session session;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seat_id", nullable = false)
    private Seat seat;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SessionSeatStatus status;

    @Column(name = "locked_at")
    private Instant lockedAt; // nullable, set on LOCKED

    @Column(nullable = false)
    private BigDecimal price; // frozen snapshot at session-creation time
}