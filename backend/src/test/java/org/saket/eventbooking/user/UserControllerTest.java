package org.saket.eventbooking.user;

import org.junit.jupiter.api.Test;
import org.saket.eventbooking.support.IntegrationTest;
import org.saket.eventbooking.user.entity.User;
import org.saket.eventbooking.user.enums.Role;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserControllerTest extends IntegrationTest {

    @Test
    void meReturnsTheAuthenticatedUser() throws Exception {
        User user = createUser(Role.USER, false);

        mockMvc.perform(get("/api/v1/users/me").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.getId().toString()))
                .andExpect(jsonPath("$.email").value(user.getEmail()))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.emailVerified").value(false))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void meWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void meForDeletedUserReturns404() throws Exception {
        User user = createUser(Role.USER, true);
        String token = bearer(user);
        userRepository.delete(user);

        mockMvc.perform(get("/api/v1/users/me").header("Authorization", token))
                .andExpect(status().isNotFound());
    }
}
