package org.saket.eventbooking.booking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code app.booking.*}.
 *
 * @param holdTtl                         how long a PENDING booking keeps its seats/capacity (default 10m)
 * @param maxTicketsPerBooking            tickets (GA quantity or seats) allowed in one booking
 * @param sweepGrace                      extra time past the TTL before the safety-net sweeper expires a hold
 * @param configureKeyspaceNotifications  try {@code CONFIG SET notify-keyspace-events} at startup
 *                                        (turn off for managed Redis that forbids CONFIG; set it there instead)
 */
@ConfigurationProperties(prefix = "app.booking")
public record BookingProperties(
        Duration holdTtl,
        int maxTicketsPerBooking,
        Duration sweepGrace,
        boolean configureKeyspaceNotifications) {
}
