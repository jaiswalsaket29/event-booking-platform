package org.saket.eventbooking.session;


import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

import java.util.UUID;

@Entity
@Table(name = "ticket_tiers")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class TicketTier {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private Session session;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private BigDecimal price;

    @Column(name = "total_capacity", nullable = false)
    private int totalCapacity;

    @Column(name = "available_capacity", nullable = false)
    private int availableCapacity;
}
