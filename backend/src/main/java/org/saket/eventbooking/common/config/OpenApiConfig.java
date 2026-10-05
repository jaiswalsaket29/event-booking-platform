package org.saket.eventbooking.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI document for springdoc (spec at {@code /v3/api-docs}, UI at {@code /swagger-ui.html}).
 * Every operation is marked as accepting a bearer JWT, so "Authorize" in Swagger UI works for both user
 * and admin endpoints; public endpoints simply ignore it.
 */
@Configuration
public class OpenApiConfig {

    static final String BEARER = "bearerAuth";

    @Bean
    public OpenAPI eventBookingOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Event Booking API")
                        .version("v1")
                        .description("""
                                Event ticket booking: catalog, seat holds, idempotent payments, admin tools.

                                Get a token from `POST /api/v1/auth/login` (dev admin: `admin@eventbooking.dev` / \
                                `Admin@12345`), then use **Authorize** with the `accessToken`. Payments need an \
                                `Idempotency-Key` header; simulated card tokens are `tok_success`, `tok_decline` \
                                and `tok_insufficient_funds`."""))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
