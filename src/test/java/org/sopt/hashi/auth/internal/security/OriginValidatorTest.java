package org.sopt.hashi.auth.internal.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OriginValidatorTest {

    private final OriginValidator validator = new OriginValidator(new CorsProperties(
            List.of("https://app.hashi.com"),
            List.of("https://hashi-client-*-example-team.vercel.app")));

    @Test
    @DisplayName("Origin이 없으면(동일 출처·비브라우저) 허용한다")
    void origin_없음_허용() {
        assertThat(validator.isAllowed(null)).isTrue();
    }

    @Test
    @DisplayName("허용 목록의 Origin은 통과한다")
    void 허용목록_통과() {
        assertThat(validator.isAllowed("https://app.hashi.com")).isTrue();
    }

    @Test
    @DisplayName("허용 패턴과 일치하는 Preview Origin은 통과한다")
    void 허용패턴_통과() {
        assertThat(validator.isAllowed(
                "https://hashi-client-preview-hash-example-team.vercel.app"))
                .isTrue();
    }

    @Test
    @DisplayName("다른 Vercel 프로젝트의 Origin은 거부한다")
    void 다른_Vercel_프로젝트_거부() {
        assertThat(validator.isAllowed(
                "https://other-project-preview-hash-example-team.vercel.app"))
                .isFalse();
    }

    @Test
    @DisplayName("허용 패턴 뒤에 도메인을 덧붙인 Origin은 거부한다")
    void 유사_악성_Origin_거부() {
        assertThat(validator.isAllowed(
                "https://hashi-client-preview-example-team.vercel.app.evil.com"))
                .isFalse();
    }

    @Test
    @DisplayName("허용 목록 밖 Origin은 거부한다")
    void 허용목록밖_거부() {
        assertThat(validator.isAllowed("https://evil.com")).isFalse();
    }
}
