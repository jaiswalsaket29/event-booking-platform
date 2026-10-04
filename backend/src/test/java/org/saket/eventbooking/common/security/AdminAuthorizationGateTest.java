package org.saket.eventbooking.common.security;

import org.junit.jupiter.api.Test;
import org.saket.eventbooking.support.IntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Every endpoint under /api/v1/admin/** is discovered from the MVC mappings (so new ones are covered
 * automatically) and must reject anonymous callers with 401 and USER tokens with 403.
 */
class AdminAuthorizationGateTest extends IntegrationTest {

    private record Endpoint(HttpMethod method, String path) {}

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    private List<Endpoint> adminEndpoints() {
        List<Endpoint> endpoints = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            for (String pattern : info.getPatternValues()) {
                if (!pattern.startsWith("/api/v1/admin/")) {
                    continue;
                }
                String path = pattern.replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
                for (RequestMethod method : info.getMethodsCondition().getMethods()) {
                    endpoints.add(new Endpoint(method.asHttpMethod(), path));
                }
            }
        }
        return endpoints;
    }

    @Test
    void discoversTheAdminEndpoints() {
        // sanity check that discovery works (locations, halls, artists, events, sessions, tiers, contact)
        assertThat(adminEndpoints()).hasSizeGreaterThan(25);
    }

    @Test
    void anonymousCallersGet401Everywhere() throws Exception {
        for (Endpoint endpoint : adminEndpoints()) {
            int status = mockMvc.perform(request(endpoint.method(), endpoint.path())
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andReturn().getResponse().getStatus();
            assertThat(status).as("%s %s", endpoint.method(), endpoint.path()).isEqualTo(401);
        }
    }

    @Test
    void regularUsersGet403Everywhere() throws Exception {
        String user = userToken();
        for (Endpoint endpoint : adminEndpoints()) {
            int status = mockMvc.perform(request(endpoint.method(), endpoint.path())
                            .header("Authorization", user)
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andReturn().getResponse().getStatus();
            assertThat(status).as("%s %s", endpoint.method(), endpoint.path()).isEqualTo(403);
        }
    }

    @Test
    void everyAdminControllerAlsoCarriesPreAuthorize() {
        // Defense in depth: the URL rule and the method-security annotation both guard admin endpoints.
        handlerMapping.getHandlerMethods().forEach((info, handler) -> {
            boolean admin = info.getPatternValues().stream().anyMatch(p -> p.startsWith("/api/v1/admin/"));
            if (admin) {
                PreAuthorize annotation = handler.getBeanType().getAnnotation(PreAuthorize.class);
                assertThat(annotation).as(handler.getBeanType().getSimpleName()).isNotNull();
                assertThat(annotation.value()).isEqualTo("hasRole('ADMIN')");
            }
        });
    }
}
