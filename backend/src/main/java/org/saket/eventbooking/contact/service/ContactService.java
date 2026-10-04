package org.saket.eventbooking.contact.service;

import lombok.RequiredArgsConstructor;
import org.saket.eventbooking.common.dto.PageResponse;
import org.saket.eventbooking.common.exception.ResourceNotFoundException;
import org.saket.eventbooking.contact.dto.ContactMessageResponse;
import org.saket.eventbooking.contact.dto.ContactRequest;
import org.saket.eventbooking.contact.entity.ContactMessage;
import org.saket.eventbooking.contact.enums.ContactMessageStatus;
import org.saket.eventbooking.contact.repository.ContactMessageRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ContactService {

    private final ContactMessageRepository contactMessageRepository;

    @Transactional
    public void submit(ContactRequest request) {
        ContactMessage message = new ContactMessage();
        message.setName(request.name().trim());
        message.setEmail(request.email().trim());
        message.setSubject(request.subject() == null || request.subject().isBlank() ? null : request.subject().trim());
        message.setMessage(request.message().trim());
        message.setStatus(ContactMessageStatus.NEW);
        message.setCreatedAt(Instant.now());
        contactMessageRepository.save(message);
    }

    @Transactional(readOnly = true)
    public PageResponse<ContactMessageResponse> list(ContactMessageStatus status, Pageable pageable) {
        var page = status == null
                ? contactMessageRepository.findAll(pageable)
                : contactMessageRepository.findByStatus(status, pageable);
        return PageResponse.of(page, ContactMessageResponse::from);
    }

    /** Idempotent: resolving an already-resolved message is a no-op. */
    @Transactional
    public ContactMessageResponse resolve(UUID id) {
        ContactMessage message = contactMessageRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Contact message", id));
        message.setStatus(ContactMessageStatus.RESOLVED);
        return ContactMessageResponse.from(message);
    }
}
