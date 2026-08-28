package org.saket.eventbooking.user;


import jakarta.persistence.*;
import org.saket.eventbooking.user.enums.AuthProvider;
import org.saket.eventbooking.user.enums.Role;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column( nullable = false,length = 255)
    private String name;

    @Column( nullable = false,unique = true)
    private String email;

//    nullable - null for oauth users
    @Column(name="password_Hash")
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;


    private String phone; // profile field only , not used for login or identity

    @Column(nullable = false,name="email_verified")
    private Boolean emailVerified = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false,name = "auth_provider" )
    private AuthProvider authProvider;

    @Column(name = "provider_id")
    private String providerId;

    @Column(name = "created_at",nullable = false)
    private Instant createdAt;
}
