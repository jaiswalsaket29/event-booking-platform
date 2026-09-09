package org.saket.eventbooking.location.entity;

import org.saket.eventbooking.location.enums.VenueType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "locations")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class Location {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(length = 500)
    private String address;

    @Column(nullable = false)
    private String city;

    @Enumerated(EnumType.STRING)
    @Column(name = "venue_type", nullable = false)
    private VenueType venueType;
}