package org.sopt.hashi.auth.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OriginValidatorTest {

    private final OriginValidator validator = new OriginValidator(List.of("https://app.hashi.com"));

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
    @DisplayName("허용 목록 밖 Origin은 거부한다")
    void 허용목록밖_거부() {
        assertThat(validator.isAllowed("https://evil.com")).isFalse();
    }
}
