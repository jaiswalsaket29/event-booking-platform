package org.saket.eventbooking.booking.hold;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.saket.eventbooking.booking.config.BookingProperties;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.util.Properties;

/**
 * Subscribes {@link HoldExpiryListener} to key-expired events on Boot's auto-configured
 * {@link RedisMessageListenerContainer}, and makes sure Redis publishes them.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HoldExpirySubscription {

    static final String EXPIRED_EVENTS = "__keyevent@*__:expired";

    private final RedisMessageListenerContainer container;
    private final HoldExpiryListener listener;
    private final RedisConnectionFactory connectionFactory;
    private final BookingProperties properties;

    @PostConstruct
    void subscribe() {
        if (properties.configureKeyspaceNotifications()) {
            enableExpiredEvents();
        }
        container.addMessageListener(listener, new PatternTopic(EXPIRED_EVENTS));
    }

    /**
     * Redis only publishes expiry events if {@code notify-keyspace-events} contains E (keyevent channel)
     * and x (expired), or A (all). Adds them to whatever is already configured. Managed Redis services
     * often forbid CONFIG; there it must be set in the provider's console (and this turned off).
     */
    private void enableExpiredEvents() {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            Properties config = connection.serverCommands().getConfig("notify-keyspace-events");
            String current = config == null ? "" : config.getProperty("notify-keyspace-events", "");
            boolean hasEvents = current.contains("E");
            boolean hasExpired = current.contains("x") || current.contains("A");
            if (hasEvents && hasExpired) {
                return;
            }
            String updated = current + (hasEvents ? "" : "E") + (hasExpired ? "" : "x");
            connection.serverCommands().setConfig("notify-keyspace-events", updated);
            log.info("Enabled Redis keyspace notifications: notify-keyspace-events={}", updated);
        } catch (RuntimeException e) {
            log.warn("Could not configure Redis keyspace notifications ({}). Hold expiry will rely on the "
                    + "sweeper unless notify-keyspace-events includes 'Ex'.", e.getMessage());
        }
    }
}
