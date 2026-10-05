package org.saket.eventbooking.media.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.saket.eventbooking.media.dto.ImageUploadOptionsResponse;
import org.saket.eventbooking.media.dto.ImageUploadRequest;
import org.saket.eventbooking.media.dto.ImageUploadResponse;
import org.saket.eventbooking.media.service.ImageUploadService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Image uploads for the admin UI: the browser uploads straight to object storage with a pre-signed URL,
 * then saves the returned {@code imageUrl} through the normal event/artist endpoints.
 */
@RestController
@RequestMapping("/api/v1/admin/uploads/images")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminImageUploadController {

    private final ImageUploadService imageUploadService;

    /** Whether direct upload is available, and its limits. Without it, the UI asks for an image URL. */
    @GetMapping
    public ImageUploadOptionsResponse options() {
        return imageUploadService.options();
    }

    /** A pre-signed PUT URL for one image; 501 when no storage is configured. */
    @PostMapping
    public ImageUploadResponse presign(@Valid @RequestBody ImageUploadRequest request) {
        return imageUploadService.presign(request);
    }
}
