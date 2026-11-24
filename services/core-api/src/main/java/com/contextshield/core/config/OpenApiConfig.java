package com.contextshield.core.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Swagger em /swagger-ui.html, já com o botão "Authorize" para colar o JWT. */
@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI contextShieldOpenApi() {
        return new OpenAPI()
                .info(new Info().title("ContextShield · Core API").version("2.0.0")
                        .description("Proteção contra compras induzidas por engenharia social. Faça POST /api/auth/login e use o token em Authorize."))
                .components(new Components().addSecuritySchemes("bearer",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}
