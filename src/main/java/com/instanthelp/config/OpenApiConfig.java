package com.instanthelp.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    public static final String BEARER = "bearerAuth";

    @Bean
    OpenAPI instantHelpOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Instant Help API")
                        .version("v1")
                        .description("""
                                Local on-demand task marketplace.

                                To try protected endpoints: call POST /v1/auth/otp/request, then \
                                POST /v1/auth/otp/verify (local profile OTP is 000000), copy the accessToken, \
                                click "Authorize" and paste it."""))
                .components(new Components().addSecuritySchemes(BEARER,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
