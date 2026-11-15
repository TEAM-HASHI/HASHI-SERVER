package org.sopt.hashi.support.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.support.code.SupportErrorCode;

class NoticeCursorTest {
    @ParameterizedTest
    @ValueSource(strings = {"YmFkfDE", "bm90LWRhdGV8MQ", "MjAyNi0xMy0wMVQxMDowMDowMHwx",
            "MjAyNi0xMC0wMVQxMDowMDowMHwtMQ", "invalid", "", "IA"})
    void 잘못된_cursor는_날짜파싱예외까지_400_도메인오류로_변환한다(String value) {
        assertThatThrownBy(() -> NoticeCursor.parse(value))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", SupportErrorCode.INVALID_CURSOR);
    }

    @Test
    void 정상_cursor는_microsecond_게시일과_ID를_왕복한다() {
        NoticeCursor cursor = new NoticeCursor(LocalDateTime.of(2026, 10, 1, 10, 0, 0, 123456000), 1L);
        assertThat(NoticeCursor.parse(cursor.encode())).isEqualTo(cursor);
    }
}