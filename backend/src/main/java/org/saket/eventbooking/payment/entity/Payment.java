package org.saket.eventbooking.payment.entity;



import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.saket.eventbooking.booking.entity.Booking;
import org.saket.eventbooking.payment.enums.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payments")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking; // @ManyToOne, not @OneToOne — a booking can have multiple payment attempts

    @Column(nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(name = "transaction_id")
    private String transactionId; // from the payment gateway, nullable until an attempt is made

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey; // client-generated, set at row creation — see Day 5 notes on why

    @Column(name = "paid_at")
    private Instant paidAt; // nullable until status = SUCCESS
}
