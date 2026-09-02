package org.saket.eventbooking.event;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "event_artists", uniqueConstraints = {
        @UniqueConstraint(name = "uq_event_artist", columnNames = {"event_id", "artist_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class EventArtist {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "artist_id", nullable = false)
    private Artist artist;

    private String role;
}