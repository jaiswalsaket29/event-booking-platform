package org.saket.eventbooking.contact.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.saket.eventbooking.common.dto.MessageResponse;
import org.saket.eventbooking.contact.dto.ContactRequest;
import org.saket.eventbooking.contact.service.ContactService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Public "Contact us" form. */
@RestController
@RequestMapping("/api/v1/contact")
@RequiredArgsConstructor
public class ContactController {

    private final ContactService contactService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MessageResponse submit(@Valid @RequestBody ContactRequest request) {
        contactService.submit(request);
        return new MessageResponse("Thanks for reaching out. We'll get back to you soon.");
    }
}
