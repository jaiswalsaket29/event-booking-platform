package org.saket.eventbooking.event.event;

import java.util.UUID;

/**
 * Published (synchronously, inside the cancelling transaction) when an event moves to CANCELLED.
 * The session domain cancels the event's scheduled sessions in response.
 */
public record EventCancelledEvent(UUID eventId) {
}
