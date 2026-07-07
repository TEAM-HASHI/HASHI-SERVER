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
    private static final String[] PUBLIC_PATHS = {
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/v3/api-docs/**",
            "/actuator/health",
            "/actuator/health/**",
            "/api/v1/auth/**"          // 로그인·재발급(후속) — 토큰 없이 접근
            // TODO(후속): 온보딩 경로는 임시 권한(ROLE_ONBOARDING)으로 별도 규칙 추가
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
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .requestMatchers(ONBOARDING_PATH).hasRole("ONBOARDING")
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        // 온보딩 임시 권한이 일반 API에 접근하지 못하도록 authenticated 대신 역할을 명시한다(auth.md §4)
                        .anyRequest().hasAnyRole("USER", "ADMIN"))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
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
