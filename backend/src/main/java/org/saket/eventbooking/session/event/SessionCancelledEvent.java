package org.saket.eventbooking.session.event;

import java.util.UUID;

/**
 * Published (synchronously, inside the cancelling transaction) when a session moves to CANCELLED.
 * The booking domain cancels the session's open and confirmed bookings in response, so the whole
 * cascade commits or rolls back together, and the session domain needs no knowledge of bookings.
 */
public record SessionCancelledEvent(UUID sessionId) {
}
