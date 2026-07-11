package com.urlshortener.api.v1.openapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

  @Value("${app.base-url:http://localhost:8080}")
  private String baseUrl;

  @Bean
  public OpenAPI openAPI() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Distributed URL Shortening Platform API")
                .version("1.0.0")
                .description(
                    """
                    Production-grade URL shortening service.

                    ## Authentication
                    Supports two authentication methods:
                    - **JWT Bearer token** — obtained via `/api/v1/auth/login`
                    - **API Key** — via `X-API-Key: sk_...` header

                    ## Rate Limits
                    - Anonymous (redirect): 10 req/min per IP
                    - Authenticated: 1000 req/hour per user
                    - API Key: configurable per key (default 1000 req/hour)
                    """)
                .contact(new Contact().name("Platform Team").email("platform@example.com"))
                .license(new License().name("MIT")))
        .servers(List.of(new Server().url(baseUrl).description("Current environment")))
        .components(
            new Components()
                .addSecuritySchemes(
                    "bearerAuth",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("JWT access token from /api/v1/auth/login"))
                .addSecuritySchemes(
                    "apiKeyAuth",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name("X-API-Key")
                        .description("API key prefixed with sk_")));
  }
}
