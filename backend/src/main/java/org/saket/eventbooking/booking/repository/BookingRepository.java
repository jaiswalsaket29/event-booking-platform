package org.saket.eventbooking.booking.repository;

import jakarta.persistence.LockModeType;
import org.saket.eventbooking.booking.entity.Booking;
import org.saket.eventbooking.booking.enums.BookingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, UUID>, JpaSpecificationExecutor<Booking> {

    /** Admin search; the graph loads what the list shows in the same query. */
    @Override
    @EntityGraph(attributePaths = {"user", "session", "session.event", "session.location", "session.hall", "ticketTier"})
    Page<Booking> findAll(Specification<Booking> spec, Pageable pageable);

    /** Rows of [bookingId, confirmedAt, totalAmount, quantity, eventId, eventTitle] for reporting. */
    @Query("""
            select b.id, b.confirmedAt, b.totalAmount, b.quantity, e.id, e.title
            from Booking b join b.session s join s.event e
            where b.status = org.saket.eventbooking.booking.enums.BookingStatus.CONFIRMED
              and b.confirmedAt >= :from and b.confirmedAt < :to
            """)
    List<Object[]> findConfirmedBetween(@Param("from") Instant from, @Param("to") Instant to);

    @EntityGraph(attributePaths = {"session", "session.event", "session.location", "session.hall", "ticketTier"})
    Page<Booking> findByUserId(UUID userId, Pageable pageable);

    @EntityGraph(attributePaths = {"session", "session.event", "session.location", "session.hall", "ticketTier"})
    Optional<Booking> findByIdAndUserId(UUID id, UUID userId);

    @EntityGraph(attributePaths = {"session", "session.event", "session.location", "session.hall", "ticketTier"})
    Optional<Booking> findWithDetailsById(UUID id);

    /** Row lock on the booking: the status check-and-transition out of PENDING happens under it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Booking b where b.id = :id")
    Optional<Booking> findByIdForUpdate(@Param("id") UUID id);

    /** Ordered by id so concurrent cascades lock bookings in the same order. */
    @Query("select b.id from Booking b where b.session.id = :sessionId and b.status in :statuses order by b.id")
    List<UUID> findIdsBySessionIdAndStatusIn(@Param("sessionId") UUID sessionId,
                                             @Param("statuses") Collection<BookingStatus> statuses);

    @Query("select b.id from Booking b where b.status = :status and b.createdAt < :before order by b.createdAt")
    List<UUID> findIdsByStatusAndCreatedAtBefore(@Param("status") BookingStatus status, @Param("before") Instant before);
}
