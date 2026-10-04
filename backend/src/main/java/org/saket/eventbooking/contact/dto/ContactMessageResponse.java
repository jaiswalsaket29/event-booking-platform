package org.saket.eventbooking.contact.dto;

import org.saket.eventbooking.contact.entity.ContactMessage;
import org.saket.eventbooking.contact.enums.ContactMessageStatus;

import java.time.Instant;
import java.util.UUID;

public record ContactMessageResponse(
        UUID id,
        String name,
        String email,
        String subject,
        String message,
        ContactMessageStatus status,
        Instant createdAt) {

    public static ContactMessageResponse from(ContactMessage m) {
        return new ContactMessageResponse(m.getId(), m.getName(), m.getEmail(), m.getSubject(), m.getMessage(),
                m.getStatus(), m.getCreatedAt());
    }
}
