package org.sopt.hashi.restaurant.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.sopt.hashi.restaurant.code.RestaurantErrorCode;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;

class MapSearchCriteriaTest {

    @Test
    void SDK의_긴_소수점_경계는_축소하거나_반올림하지_않는다() {
        MapQueryBounds bounds = MapQueryBounds.parse("0.000000000001", "0.999999999999",
                "0.000000000002", "0.999999999998");
        bounds.validateQueryWithin(MapQueryBounds.parse("0", "1", "0", "1"));
        assertThat(bounds.south()).isEqualByComparingTo("0.000000000001");
        assertThat(bounds.east()).isEqualByComparingTo("0.999999999998");
    }

    @ParameterizedTest
    @CsvSource({"NaN,1,0,1", "0,Infinity,0,1", "-91,0,0,1", "0,91,0,1", "0,1,-181,0",
            "0,1,0,181", "1,0,0,1", "0,0,0,1", "0,1,180,-180", "0,1,1,1"})
    void 유한하지_않거나_범위_역전_날짜변경선_경계는_거절한다(String south, String north, String west, String east) {
        assertThatThrownBy(() -> MapQueryBounds.parse(south, north, west, east))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(RestaurantErrorCode.MAP_BOUNDS_INVALID));
    }

    @ParameterizedTest
    @CsvSource({"0,1.00000001,0,1", "0,1,0,1.00000001", "-0.000001,0.5,0,1"})
    void 일도_초과와_지원영역_밖을_거절한다(String south, String north, String west, String east) {
        assertThatThrownBy(() -> MapQueryBounds.parse(south, north, west, east)
                .validateQueryWithin(MapQueryBounds.parse("0", "1", "0", "1")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void Unicode_공백과_특수문자를_정규화하고_100자를_코드포인트로_센다() {
        var criteria = criteria("  A\u00a0\u3000B %_!\\  ");
        assertThat(criteria.keyword()).isEqualTo("A B %_!\\");
        assertThat(criteria.keywordPattern()).isEqualTo("%a b !%!_!!\\%");
        assertThat(criteria("가".repeat(100)).keyword()).hasSize(100);
        assertThat(criteria("😀".repeat(100)).keyword().codePointCount(0, 200)).isEqualTo(100);
        assertThatThrownBy(() -> criteria("가".repeat(101))).isInstanceOf(BusinessException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "a\nb", "a\tb", "a\rb", "a\u0000b", "a\u007fb", "a\u2028b", "a\u200bb"})
    void 빈_검색어와_제어문자를_거절한다(String keyword) {
        assertThatThrownBy(() -> criteria(keyword)).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "all", "SUSHI", "foodCategory"})
    void 장르는_고정된_wire_값만_허용한다(String genre) {
        assertThatThrownBy(() -> MapSearchCriteria.of(bounds(), null, genre, null, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(RestaurantErrorCode.UNSUPPORTED_GENRE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "all", "CAFE", "sushi"})
    void 음식점_분류는_고정된_wire_값만_허용한다(String placeType) {
        assertThatThrownBy(() -> MapSearchCriteria.of(bounds(), null, null, placeType, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(RestaurantErrorCode.UNSUPPORTED_PLACE_TYPE));
    }

    @Test
    void 지역_ID는_양수이며_필터_생략은_null로_유지한다() {
        assertThatThrownBy(() -> MapSearchCriteria.of(bounds(), 0L, null, null, null))
                .isInstanceOf(BusinessException.class);
        assertThat(criteria(null).keywordPattern()).isNull();
    }

    private MapSearchCriteria criteria(String keyword) {
        return MapSearchCriteria.of(bounds(), null, null, null, keyword);
    }

    private MapQueryBounds bounds() {
        return MapQueryBounds.parse("0", "1", "0", "1");
    }
}
