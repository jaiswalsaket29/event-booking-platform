package org.saket.eventbooking.common.security;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


// TEMPORARY — verification endpoint for JWT filter testing.
// Remove once real protected endpoints exist (admin CRUD) to test against instead.
@RestController
@RequestMapping("/api/v1/test")
public class TestController {

    @GetMapping("/protected")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public String protectedEndpoint() {
        return "You're authenticated!";
    }
}