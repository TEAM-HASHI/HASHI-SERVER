package org.sopt.hashi.restaurant.service;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sopt.hashi.restaurant.domain.*;
import org.sopt.hashi.shared.error.BusinessException;

class RestaurantSearchCursorCodecTest {
    private final RestaurantSearchQuery query = RestaurantSearchQuery.parse("스시 라멘");
    private String fingerprint() {
        return RestaurantSearchCursorCodec.fingerprint(query, null, RestaurantListType.ALL, RestaurantSort.BASIC);
    }

    @Test
    void 최대_커서도_200자_안이며_경계값이_보존된다() {
        var values = List.of(new BigDecimal("15"), new BigDecimal("15"), new BigDecimal("15"),
                new BigDecimal("15"), BigDecimal.valueOf(Long.MAX_VALUE));
        String token = RestaurantSearchCursorCodec.encode(values, fingerprint());
        assertThat(token.length()).isLessThanOrEqualTo(200);
        assertThat(RestaurantSearchCursorCodec.decode(token, fingerprint(), RestaurantSort.BASIC, 15)).isEqualTo(values);
    }

    @Test
    void 조건이_다르거나_예전_커서는_거부한다() {
        String token = RestaurantSearchCursorCodec.encode(List.of(BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.TEN), fingerprint());
        assertThatThrownBy(() -> RestaurantSearchCursorCodec.decode(token, "different", RestaurantSort.BASIC, 2))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> RestaurantSearchCursorCodec.decode("QkFTSUN8fHwx", fingerprint(), RestaurantSort.BASIC, 2))
                .isInstanceOf(BusinessException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1|0|0|0|1", "3|0|0|0|1", "1|2|0|0|1", "1|0|0|0|0",
            "1|0|0|0|9223372036854775808", "1.1|0|0|0|1"})
    void 유효하지_않은_정렬경계값은_거부한다(String values) {
        String raw = "S1|" + fingerprint() + "|" + values;
        String token = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> RestaurantSearchCursorCodec.decode(token, fingerprint(), RestaurantSort.BASIC, 2))
                .isInstanceOf(BusinessException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"#", "# sushi", "#스시 라멘", "1234567890123456789012345678901"})
    void 잘못된_검색어는_거부한다(String keyword) {
        assertThatThrownBy(() -> RestaurantSearchQuery.parse(keyword)).isInstanceOf(BusinessException.class);
    }

    @Test
    void 공백과_대소문자_중복어를_정규화한다() {
        assertThat(RestaurantSearchQuery.parse("  Sushi\u00a0SUSHI 라멘  ").terms()).containsExactly("sushi", "라멘");
        assertThat(RestaurantSearchQuery.parse("\u00a0 ").isEmpty()).isTrue();
        assertThat(RestaurantSearchQuery.parse(" #SUSHI ").hashtagOnly()).isTrue();
    }
}
