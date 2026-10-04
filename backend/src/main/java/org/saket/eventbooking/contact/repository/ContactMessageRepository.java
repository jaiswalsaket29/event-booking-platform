package org.saket.eventbooking.contact.repository;

import org.saket.eventbooking.contact.entity.ContactMessage;
import org.saket.eventbooking.contact.enums.ContactMessageStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ContactMessageRepository extends JpaRepository<ContactMessage, UUID> {

    Page<ContactMessage> findByStatus(ContactMessageStatus status, Pageable pageable);
}
