package com.theninjadev.ajoapi.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI metadata, bearer auth for the "Authorize" button, and the two response
 * conventions every endpoint shares — so controllers only document what is specific to them.
 */
@Configuration
@OpenAPIDefinition(security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
@SecurityScheme(
        name = OpenApiConfig.BEARER_AUTH,
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        description = "Access token from /auth/register, /auth/login or /auth/refresh.")
public class OpenApiConfig {

    static final String BEARER_AUTH = "bearerAuth";

    private static final String PROBLEM_DETAIL = "ProblemDetail";
    private static final String PROBLEM_JSON = "application/problem+json";

    @Bean
    public OpenAPI ajoOpenApi(@Value("${info.app.version:unknown}") String version) {
        return new OpenAPI()
                .info(new Info()
                        .title("Ajo API")
                        .version(version)
                        .description("""
                                Backend for rotating savings circles — the Nigerian Ajo, also called \
                                Esusu or Adashe. A group agrees a fixed monthly amount and a payout \
                                order; every member contributes each month and one member collects the \
                                whole pot, until everyone has collected exactly once. There is no \
                                interest and no fee: the circle moves money through time, not between \
                                people. All amounts are integer kobo (₦10,000 is 1000000).""")
                        .contact(new Contact().name("theNinjaDev").url("https://github.com/samimkabiru/Ajo-api")))
                .components(new Components().addSchemas(PROBLEM_DETAIL, problemDetailSchema()));
    }

    /**
     * Every authenticated operation can return 401 — from the security entry point, not from
     * GlobalExceptionHandler — and every 4xx body is an RFC 9457 ProblemDetail. Controllers
     * document error codes and meanings only; the body schema is set here.
     */
    @Bean
    public OpenApiCustomizer sharedResponses() {
        return openApi -> openApi.getPaths().values().forEach(path ->
                path.readOperations().forEach(operation -> {
                    boolean secured = operation.getSecurity() == null || !operation.getSecurity().isEmpty();
                    if (secured && (operation.getResponses() == null
                            || !operation.getResponses().containsKey("401"))) {
                        operation.getResponses().addApiResponse("401",
                                new ApiResponse().description("Missing, invalid or expired access token."));
                    }

                    // springdoc fills every documented response with the method's return type;
                    // for errors that is wrong — the body is always a ProblemDetail.
                    operation.getResponses().forEach((code, response) -> {
                        if (code.startsWith("4")) {
                            response.setContent(new Content().addMediaType(PROBLEM_JSON,
                                    new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + PROBLEM_DETAIL))));
                        }
                    });
                }));
    }

    private static Schema<?> problemDetailSchema() {
        return new ObjectSchema()
                .description("Error body (RFC 9457). `detail` carries the human-readable reason.")
                .addProperty("type", new StringSchema().example("about:blank"))
                .addProperty("title", new StringSchema().example("Conflict"))
                .addProperty("status", new IntegerSchema().example(409))
                .addProperty("detail", new StringSchema().example("This cycle has already been paid out"))
                .addProperty("instance", new StringSchema().example("/cycles/3f0c…/payout"));
    }
}
