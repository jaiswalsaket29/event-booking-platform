package org.saket.eventbooking.contact.controller;

import lombok.RequiredArgsConstructor;
import org.saket.eventbooking.common.dto.PageResponse;
import org.saket.eventbooking.contact.dto.ContactMessageResponse;
import org.saket.eventbooking.contact.enums.ContactMessageStatus;
import org.saket.eventbooking.contact.service.ContactService;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/contact-messages")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminContactController {

    private final ContactService contactService;

    /** Newest first; filter with {@code ?status=NEW} for the triage queue. */
    @GetMapping
    public PageResponse<ContactMessageResponse> list(@RequestParam(required = false) ContactMessageStatus status,
                                                     @PageableDefault(size = 20, sort = "createdAt",
                                                             direction = Sort.Direction.DESC) Pageable pageable) {
        return contactService.list(status, pageable);
    }

    @PatchMapping("/{id}/resolve")
    public ContactMessageResponse resolve(@PathVariable UUID id) {
        return contactService.resolve(id);
    }
}
