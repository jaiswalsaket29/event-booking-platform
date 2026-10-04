package org.saket.eventbooking.booking.repository;

import org.saket.eventbooking.booking.entity.BookingSeat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface BookingSeatRepository extends JpaRepository<BookingSeat, UUID> {

    @Query("""
            select bs from BookingSeat bs
            join fetch bs.sessionSeat ss
            join fetch ss.seat
            where bs.booking.id in :bookingIds
            """)
    List<BookingSeat> findByBookingIdsWithSeats(@Param("bookingIds") Collection<UUID> bookingIds);

    @Query("select bs.sessionSeat.id from BookingSeat bs where bs.booking.id = :bookingId")
    List<UUID> findSessionSeatIdsByBookingId(@Param("bookingId") UUID bookingId);
}
