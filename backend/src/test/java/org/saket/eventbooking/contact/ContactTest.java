package org.saket.eventbooking.contact;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.saket.eventbooking.support.IntegrationTest;
import org.springframework.http.MediaType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ContactTest extends IntegrationTest {

    @Test
    void anonymousSubmissionThenAdminTriage() throws Exception {
        String email = uniqueEmail();
        mockMvc.perform(post("/api/v1/contact")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Ishaan Mehta", "email": "%s", "subject": "Refund?", "message": "My show moved."}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").exists());

        String admin = adminToken();
        String page = mockMvc.perform(get("/api/v1/admin/contact-messages")
                        .header("Authorization", admin).param("status", "NEW").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].email", hasItem(email)))
                .andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(page, "$.content[?(@.email == '%s')].id".formatted(email));
        assertThat(ids).hasSize(1);
        String id = ids.getFirst();

        mockMvc.perform(patch("/api/v1/admin/contact-messages/{id}/resolve", id).header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"));
        // idempotent
        mockMvc.perform(patch("/api/v1/admin/contact-messages/{id}/resolve", id).header("Authorization", admin))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/admin/contact-messages")
                        .header("Authorization", admin).param("status", "NEW").param("size", "100"))
                .andExpect(jsonPath("$.content[*].email", not(hasItem(email))));
        mockMvc.perform(get("/api/v1/admin/contact-messages")
                        .header("Authorization", admin).param("status", "RESOLVED").param("size", "100"))
                .andExpect(jsonPath("$.content[*].email", hasItem(email)));
    }

    @Test
    void invalidSubmissionIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/contact")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"X\", \"email\": \"nope\", \"message\": \"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("email")))
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("message")));
    }

    @Test
    void contactListIsAdminOnly() throws Exception {
        mockMvc.perform(get("/api/v1/admin/contact-messages")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/contact-messages").header("Authorization", userToken()))
                .andExpect(status().isForbidden());
    }
}
