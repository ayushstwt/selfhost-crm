package com.ayshriv.selfhostcrm.config;

import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI documentation: bearer scheme plus example responses for the shared {@code ApiResponse}
 * envelope (success, paginated success and the common errors). Disabled in the prod profile.
 */
@Configuration
public class OpenApiConfig {

	public static final String BEARER = "bearerAuth";

	public static final String REF = "#/components/examples/";

	public static final String EX_LOGIN = "LoginSuccess";

	public static final String EX_ME = "CurrentUser";

	public static final String EX_PAGE = "ActivityPage";

	public static final String EX_VALIDATION = "ValidationError";

	public static final String EX_UNAUTHORIZED = "AuthenticationRequired";

	public static final String EX_INVALID_CREDENTIALS = "InvalidCredentials";

	public static final String EX_FORBIDDEN = "AccessDenied";

	public static final String EX_INTERNAL = "InternalError";



	@Bean
	OpenAPI selfhostCrmOpenApi() {
		return new OpenAPI()
				.info(new Info().title("Selfhost CRM API").version("v1")
						.description("Every response uses one envelope: status, message, errorCode (errors only), "
								+ "data, metadata (lists), errors (validation), path (errors) and timestamp. "
								+ "Clients must branch on the HTTP status code, not on the body's status field."))
				.addSecurityItem(new SecurityRequirement().addList(BEARER))
				.components(new Components()
						.addSecuritySchemes(BEARER, new SecurityScheme().type(SecurityScheme.Type.HTTP)
								.scheme("bearer").bearerFormat("JWT"))
						.addExamples(EX_LOGIN, example("""
								{"status":"success","message":"Login successful",
								 "data":{"accessToken":"eyJhbGciOiJIUzI1NiJ9...","refreshToken":"Q0b3...43 chars...",
								         "tokenType":"Bearer","expiresIn":900},
								 "timestamp":"2026-11-17T10:15:30Z"}"""))
						.addExamples(EX_ME, example("""
								{"status":"success","message":"Current user retrieved successfully",
								 "data":{"id":1,"email":"admin@example.com","displayName":"Root Admin","role":"ADMIN",
								         "enabled":true,"lastLoginAt":"2026-11-17T10:15:30Z",
								         "createdOn":"2026-11-01T08:00:00Z"},
								 "timestamp":"2026-11-17T10:15:30Z"}"""))
						.addExamples(EX_PAGE, example("""
								{"status":"success","message":"Activity retrieved successfully",
								 "data":[{"id":42,"createdOn":"2026-11-17T10:15:30Z","action":"LOGIN",
								          "subAction":"SUCCESS","userName":"Root Admin","userRole":"ADMIN"}],
								 "metadata":{"currentPage":0,"pageSize":20,"totalPages":1,"totalItems":1},
								 "timestamp":"2026-11-17T10:15:31Z"}"""))
						.addExamples(EX_VALIDATION, example("""
								{"status":"error","message":"Validation failed","errorCode":"E005","data":null,
								 "errors":[{"field":"email","message":"Email is required"}],
								 "path":"/api/auth/login","timestamp":"2026-11-17T10:15:30Z"}"""))
						.addExamples(EX_UNAUTHORIZED, example("""
								{"status":"error","message":"Authentication required","errorCode":"E003","data":null,
								 "path":"/api/auth/me","timestamp":"2026-11-17T10:15:30Z"}"""))
						.addExamples(EX_INVALID_CREDENTIALS, example("""
								{"status":"error","message":"Invalid email or password","errorCode":"E006","data":null,
								 "path":"/api/auth/login","timestamp":"2026-11-17T10:15:30Z"}"""))
						.addExamples(EX_FORBIDDEN, example("""
								{"status":"error","message":"Access denied","errorCode":"E004","data":null,
								 "path":"/api/example","timestamp":"2026-11-17T10:15:30Z"}"""))
						.addExamples(EX_INTERNAL, example("""
								{"status":"error","message":"An unexpected error occurred","errorCode":"E500","data":null,
								 "path":"/api/example","timestamp":"2026-11-17T10:15:30Z"}""")));
	}

	/** Adds the shared error responses to every operation that does not declare them itself. */
	@Bean
	OperationCustomizer envelopeErrorResponses() {
		return (operation, handlerMethod) -> {
			ApiResponses responses = operation.getResponses() == null ? new ApiResponses() : operation.getResponses();
			boolean secured = operation.getSecurity() == null || !operation.getSecurity().isEmpty();
			responses.putIfAbsent("400", errorResponse("Validation failed or malformed request", EX_VALIDATION));
			if (secured) {
				responses.putIfAbsent("401", errorResponse("Missing, invalid or expired access token", EX_UNAUTHORIZED));
				responses.putIfAbsent("403", errorResponse("Authenticated but not allowed", EX_FORBIDDEN));
			}
			responses.putIfAbsent("500", errorResponse("Unexpected server error (generic message)", EX_INTERNAL));
			operation.setResponses(responses);
			return operation;
		};
	}

	private static ApiResponse errorResponse(String description, String exampleName) {
		MediaType json = new MediaType().addExamples(exampleName, new Example().$ref("#/components/examples/" + exampleName));
		return new ApiResponse().description(description).content(new Content().addMediaType("application/json", json));
	}

	private static Example example(String json) {
		try {
			return new Example().value(Json.mapper().readValue(json, Object.class));
		}
		catch (Exception e) {
			throw new IllegalStateException("Invalid OpenAPI example", e);
		}
	}


}
