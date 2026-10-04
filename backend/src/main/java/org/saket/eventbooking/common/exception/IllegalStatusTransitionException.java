package org.saket.eventbooking.common.exception;

import java.util.UUID;

/**
 * A status change the lifecycle doesn't allow (e.g. CONFIRMED -> PENDING). Services check state
 * before transitioning, so reaching this means a race or a bug; it maps to 409 and rolls back.
 */
public class IllegalStatusTransitionException extends RuntimeException {

    public IllegalStatusTransitionException(String entity, UUID id, Enum<?> from, Enum<?> to) {
        super(entity + " " + id + " can't move from " + from + " to " + to);
    }
}
