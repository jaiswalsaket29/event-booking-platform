package org.saket.eventbooking.session.repository;

import org.saket.eventbooking.session.entity.SessionSeat;
import org.saket.eventbooking.session.enums.SessionSeatStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface SessionSeatRepository extends JpaRepository<SessionSeat, UUID> {

    @Query("select ss from SessionSeat ss join fetch ss.seat where ss.session.id = :sessionId")
    List<SessionSeat> findBySessionIdWithSeat(@Param("sessionId") UUID sessionId);

    /** Rows of [sessionId, count] for the given sessions; sessions with no matching seats are absent. */
    @Query("""
            select ss.session.id, count(ss) from SessionSeat ss
            where ss.session.id in :sessionIds and ss.status = :status
            group by ss.session.id
            """)
    List<Object[]> countBySessionIdsAndStatus(@Param("sessionIds") Collection<UUID> sessionIds,
                                             @Param("status") SessionSeatStatus status);

    /**
     * Locks the requested seats of one session. Ordered by id so two checkouts that want overlapping
     * seats always lock them in the same order and can't deadlock. No join fetch: joining {@code seat}
     * would also lock the shared physical {@code seats} rows and make different showtimes contend.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select ss from SessionSeat ss where ss.session.id = :sessionId and ss.id in :ids order by ss.id")
    List<SessionSeat> lockBySessionIdAndIds(@Param("sessionId") UUID sessionId, @Param("ids") Collection<UUID> ids);

    @Modifying(flushAutomatically = true)
    @Query("delete from SessionSeat ss where ss.session.id = :sessionId")
    int deleteBySessionId(@Param("sessionId") UUID sessionId);
}
