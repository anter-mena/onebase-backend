package com.onebase.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/**
 * The title Swagger shows, and how its "Try it out" button signs in.
 *
 * <p>Opening Swagger needs the docs password (browser box). Calling the API
 * from it needs a real sign-in: run {@code POST /api/auth/login}, copy the
 * {@code accessToken}, click <b>Authorize</b> and paste it.
 */
@Configuration
@OpenAPIDefinition(
	info = @Info(title = "One Base API", version = "v1", description = "Backend of the One Base CRM."),
	security = @SecurityRequirement(name = "bearerAuth"))
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class OpenApiConfig {
}
