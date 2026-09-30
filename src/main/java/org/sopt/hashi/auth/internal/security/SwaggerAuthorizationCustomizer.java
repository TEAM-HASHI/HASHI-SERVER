package org.sopt.hashi.auth.internal.security;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import java.util.Arrays;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

/**
 * {@link SecurityConfig}의 공개 경로 목록을 그대로 읽어, 인증이 필요한 오퍼레이션에만
 * Bearer 인증 요구(Swagger 자물쇠)를 자동 부여한다. 엔드포인트별 {@code @SecurityRequirement}
 * 선언이 필요 없고, 인증 정책이 바뀌면 문서가 같은 소스로 함께 바뀐다.
 */
@Component
public class SwaggerAuthorizationCustomizer implements GlobalOpenApiCustomizer {

    /** SwaggerConfig가 등록한 Bearer 스킴 이름과 일치해야 한다. */
    private static final String BEARER_SCHEME_NAME = "bearerAuth";

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi.getPaths() == null) {
            return;
        }
        openApi.getPaths().forEach((path, pathItem) -> pathItem.readOperationsMap().forEach((method, operation) -> {
            if (requiresAuthentication(path, method)) {
                operation.addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME_NAME));
            }
        }));
    }

    /**
     * SecurityConfig 매처와 같은 판정 — auth/me·매거진 좋아요는 공개 경로 하위지만 인증이 필요해 먼저 보고,
     * 식당 컬렉션 상세·저장 식당 목록은 GET만 공개다(#216).
     */
    private boolean requiresAuthentication(String path, PathItem.HttpMethod method) {
        boolean isAuthenticatedUnderPublicPath = pathMatcher.match(SecurityConfig.AUTH_ME_PATH, path)
                || pathMatcher.match(SecurityConfig.MAGAZINE_LIKE_PATH, path);
        if (isAuthenticatedUnderPublicPath) {
            return true;
        }
        boolean isPublicCollectionRead = method == PathItem.HttpMethod.GET
                && Arrays.stream(SecurityConfig.COLLECTION_PUBLIC_GET_PATHS)
                        .anyMatch(publicPath -> pathMatcher.match(publicPath, path));
        if (isPublicCollectionRead) {
            return false;
        }
        return Arrays.stream(SecurityConfig.PUBLIC_PATHS)
                .noneMatch(publicPath -> pathMatcher.match(publicPath, path));
    }
}
