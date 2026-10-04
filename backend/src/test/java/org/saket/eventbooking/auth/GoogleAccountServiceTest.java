package org.saket.eventbooking.auth;

import org.junit.jupiter.api.Test;
import org.saket.eventbooking.auth.exception.OAuth2LoginRejectedException;
import org.saket.eventbooking.auth.oauth2.GoogleProfile;
import org.saket.eventbooking.auth.service.GoogleAccountService;
import org.saket.eventbooking.support.IntegrationTest;
import org.saket.eventbooking.user.entity.User;
import org.saket.eventbooking.user.enums.AuthProvider;
import org.saket.eventbooking.user.enums.Role;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoogleAccountServiceTest extends IntegrationTest {

    @Autowired
    GoogleAccountService googleAccountService;

    private static String sub() {
        return "google-" + UUID.randomUUID();
    }

    @Test
    void newGoogleUserIsRegisteredVerifiedWithoutPassword() {
        String email = uniqueEmail();
        User user = googleAccountService.loginOrRegister(new GoogleProfile(sub(), email, true, "Riya Kapoor"));

        assertThat(user.getAuthProvider()).isEqualTo(AuthProvider.GOOGLE);
        assertThat(user.getRole()).isEqualTo(Role.USER);
        assertThat(user.getEmailVerified()).isTrue();
        assertThat(user.getPasswordHash()).isNull();
        assertThat(user.getName()).isEqualTo("Riya Kapoor");
    }

    @Test
    void repeatLoginFindsTheSameUserBySubject() {
        String subject = sub();
        User first = googleAccountService.loginOrRegister(new GoogleProfile(subject, uniqueEmail(), true, "A"));
        // even if the Google email changed since, the subject is the stable key
        User second = googleAccountService.loginOrRegister(new GoogleProfile(subject, uniqueEmail(), true, "A"));

        assertThat(second.getId()).isEqualTo(first.getId());
    }

    @Test
    void existingLocalAccountIsLinkedWhenGoogleVerifiedTheEmail() {
        User local = createUser(Role.USER, false);
        String subject = sub();

        User linked = googleAccountService.loginOrRegister(
                new GoogleProfile(subject, local.getEmail().toUpperCase(), true, "Whoever"));

        assertThat(linked.getId()).isEqualTo(local.getId());
        User reloaded = userRepository.findById(local.getId()).orElseThrow();
        assertThat(reloaded.getProviderId()).isEqualTo(subject);
        assertThat(reloaded.getEmailVerified()).isTrue();
        assertThat(reloaded.getAuthProvider()).isEqualTo(AuthProvider.LOCAL);
    }

    @Test
    void existingAccountIsNotLinkedWhenGoogleHasNotVerifiedTheEmail() {
        User local = createUser(Role.USER, false);

        assertThatThrownBy(() -> googleAccountService.loginOrRegister(
                new GoogleProfile(sub(), local.getEmail(), false, "Attacker")))
                .isInstanceOf(OAuth2LoginRejectedException.class);
        assertThat(userRepository.findById(local.getId()).orElseThrow().getProviderId()).isNull();
    }

    @Test
    void adminsCannotSignInWithGoogle() {
        User admin = createUser(Role.ADMIN, true);

        assertThatThrownBy(() -> googleAccountService.loginOrRegister(
                new GoogleProfile(sub(), admin.getEmail(), true, "Admin")))
                .isInstanceOf(OAuth2LoginRejectedException.class);
    }
}
