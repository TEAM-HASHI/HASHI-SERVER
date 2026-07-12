package org.sopt.hashi.auth.internal.security;
import org.sopt.hashi.auth.internal.kakao.KakaoProperties;
import org.sopt.hashi.auth.internal.jwt.JwtProperties;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * 인증 강제는 이 필터 체인이 전담한다(도메인 모듈은 인증 로직을 갖지 않는다 — auth.md §1).
 * 무상태(JWT) 세션·어드민 ROLE 요구·공개 경로(permitAll)·401/403 봉투 응답을 구성한다.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties({JwtProperties.class, KakaoProperties.class})
public class SecurityConfig {

    private static final String ONBOARDING_PATH = "/api/v1/users/onboarding";
    /** 내 인증 정보 조회 — /api/v1/auth/**(permitAll) 아래에 있지만 인증이 필요해 예외로 먼저 매칭한다. */
    static final String AUTH_ME_PATH = "/api/v1/auth/me";
    /** presigned URL 발급 — 온보딩(프로필 사진 업로드) 단계에서도 필요해 임시 권한까지 허용한다. */
    private static final String UPLOAD_PATH = "/api/v1/uploads/**";
    /** 공개 경로 단일 소스 — {@link SwaggerAuthorizationCustomizer}가 같은 목록으로 문서 자물쇠를 판정한다. */
    static final String[] PUBLIC_PATHS = {
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/v3/api-docs/**",
            "/actuator/health",
            "/actuator/health/**",
            "/api/v1/auth/**",         // 로그인·재발급(후속) — 토큰 없이 접근
            // 비로그인 탐색 화면(#93) — 식당 조회(하위 리뷰 목록 포함)와 매거진 조회는 공개
            "/api/v1/restaurants/**",
            "/api/v1/magazines/**"
    };

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           JwtAuthenticationFilter jwtAuthenticationFilter,
                                           JwtAuthenticationEntryPoint authenticationEntryPoint,
                                           JwtAccessDeniedHandler accessDeniedHandler) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // permitAll(/api/v1/auth/**)보다 먼저 매칭해야 무인증 통과를 막는다.
                        // 클라 진입 라우팅용 상태 조회라 온보딩 임시 토큰도 허용한다(auth.md §4의 명시적 예외) —
                        // 응답은 subjectId·role이며, 온보딩만 subject(kakaoId)를 노출하지 않아 subjectId가 null이다.
                        .requestMatchers(AUTH_ME_PATH).hasAnyRole("USER", "ADMIN", "ONBOARDING")
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .requestMatchers(ONBOARDING_PATH).hasRole("ONBOARDING")
                        .requestMatchers(UPLOAD_PATH).hasAnyRole("USER", "ADMIN", "ONBOARDING")
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        // 역할을 명시해 온보딩 임시 권한(ROLE_ONBOARDING)의 일반 API 접근을 차단하고(auth.md §4),
                        // 어드민 토큰도 일반 사용자 API를 호출하지 못하게 한다(adminId가 userId로 오인되는 것 방지 —
                        // 어드민은 /api/v1/admin/** 진입점으로만 행동한다, architecture.md §9)
                        .anyRequest().hasRole("USER"))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    /** 어드민 ID/PW 검증용 단방향 해시. BCrypt는 솔트 내장·연산 비용 조절로 무차별 대입을 어렵게 한다. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 리프레시 쿠키(HttpOnly) 전송을 위해 allowCredentials를 켠다 — 오리진은 와일드카드 불가, 정확한 도메인만 나열한다.
     * 허용 오리진은 환경별로 다르므로 설정(hashi.cors.allowed-origins, env CORS_ALLOWED_ORIGINS)으로 주입한다.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${hashi.cors.allowed-origins}") List<String> allowedOrigins) {
        CorsConfiguration configuration = new CorsConfiguration();

        // 프론트엔드 도메인 허용
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        // 교차 출처에서 프론트가 응답의 액세스 토큰(Authorization 헤더)을 읽을 수 있게 노출한다.
        configuration.setExposedHeaders(List.of(HttpHeaders.AUTHORIZATION));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
