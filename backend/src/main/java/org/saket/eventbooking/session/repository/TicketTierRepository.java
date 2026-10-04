package org.saket.eventbooking.session.repository;

import org.saket.eventbooking.session.entity.TicketTier;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TicketTierRepository extends JpaRepository<TicketTier, UUID> {

    List<TicketTier> findBySessionIdOrderByPriceAscNameAsc(UUID sessionId);

    List<TicketTier> findBySessionIdIn(Collection<UUID> sessionIds);

    /** Row lock so an admin capacity edit can't interleave with a booking decrementing the same tier. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TicketTier t join fetch t.session where t.id = :id")
    Optional<TicketTier> findByIdForUpdate(@Param("id") UUID id);

    @Modifying(flushAutomatically = true)
    @Query("delete from TicketTier t where t.session.id = :sessionId")
    int deleteBySessionId(@Param("sessionId") UUID sessionId);
}
