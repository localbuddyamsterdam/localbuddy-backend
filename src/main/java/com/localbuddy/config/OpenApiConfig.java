package com.localbuddy.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI localBuddyOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("LocalBuddy API")
                        .version("1.0")
                        .description("""
                                LocalBuddy backend API.

                                Authentication: most endpoints require a Bearer JWT. Obtain a token via
                                POST /api/auth/login (or /api/auth/social), click the **Authorize** button,
                                and paste the `accessToken`. Endpoints under /api/public/** and /api/auth/**
                                are open and need no token."""))
                // Registering the scheme as a component makes the "Authorize" button appear in
                // Swagger UI; the global security item attaches the bearer token to requests so
                // secured endpoints can be exercised directly ("Try it out").
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .name(BEARER_SCHEME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Paste the accessToken returned by /api/auth/login")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
