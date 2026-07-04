package org.sopt.hashi.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SpringDoc/Swagger 배선. API 메타 정보와 JWT Bearer 인증(Authorize 버튼)을 문서에 노출한다.
 */
@Configuration
public class SwaggerConfig {

    private static final String BEARER_SCHEME_NAME = "bearerAuth";
    private static final String API_BASE_PATH = "/api/v1/**";
    private static final String ADMIN_BASE_PATH = "/api/v1/admin/**";

    /** API 메타 정보와 JWT Bearer 인증 스킴(Authorize 버튼)을 정의한다. */
    @Bean
    public OpenAPI openAPI() {
        SecurityScheme bearerScheme = new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .in(SecurityScheme.In.HEADER)
                .name("Authorization");

        return new OpenAPI()
                .info(new Info()
                        .title("HASHI API")
                        .description("HASHI API 문서")
                        .version("v1"))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME_NAME, bearerScheme))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME_NAME));
    }

    /** @ApiExceptions 기반 에러 응답 예시 자동 문서화를 springdoc에 등록한다. */
    @Bean
    public OperationCustomizer apiExceptionsOperationCustomizer() {
        return new ApiExceptionsOperationCustomizer();
    }

    /** 사용자 API 문서 그룹(/api/v1/** 중 admin 제외). */
    @Bean
    public GroupedOpenApi userApi() {
        return GroupedOpenApi.builder()
                .group("user")
                .pathsToMatch(API_BASE_PATH)
                .pathsToExclude(ADMIN_BASE_PATH)
                .build();
    }

    /** 어드민 API 문서 그룹(/api/v1/admin/**). */
    @Bean
    public GroupedOpenApi adminApi() {
        return GroupedOpenApi.builder()
                .group("admin")
                .pathsToMatch(ADMIN_BASE_PATH)
                .build();
    }
}
