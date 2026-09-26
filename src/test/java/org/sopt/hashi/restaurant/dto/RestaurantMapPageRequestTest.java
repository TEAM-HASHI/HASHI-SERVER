package org.sopt.hashi.restaurant.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import java.math.BigDecimal;
import java.time.Duration;
import org.sopt.hashi.restaurant.code.RestaurantErrorCode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sopt.hashi.restaurant.domain.RestaurantMapSort;
import org.sopt.hashi.shared.error.BusinessException;
import org.springframework.util.LinkedMultiValueMap;

class RestaurantMapPageRequestTest {
    @Test
    void BBOX와_정규화조건을_공유하고_정렬은_추천을_기본값으로_한다() {
        var parameters = bounds();
        parameters.add("keyword", "  sushi   A ");
        var request = RestaurantMapPageRequest.from(parameters);
        assertThat(request.criteria().keyword()).isEqualTo("sushi A");
        assertThat(request.sort()).isEqualTo(RestaurantMapSort.RECOMMEND);
        assertThat(request.toString()).doesNotContain("sushi");
    }

    @ParameterizedTest
    @ValueSource(strings = {"size", "unknown", "foodCategory", "cursor", "querySessionId", "south"})
    void 미지원_혼합_중복_파라미터를_거절한다(String parameter) {
        var parameters = bounds();
        parameters.add(parameter, "1");
        assertThatThrownBy(() -> RestaurantMapPageRequest.from(parameters)).isInstanceOf(BusinessException.class);
    }

    @Test
    void cursor만_혹은_세션과정렬만_허용한다() {
        var parameters = new LinkedMultiValueMap<String, String>();
        parameters.add("cursor", "cursor");
        assertThat(RestaurantMapPageRequest.from(parameters).cursor()).isEqualTo("cursor");
        parameters.add("sort", "rating");
        assertThatThrownBy(() -> RestaurantMapPageRequest.from(parameters)).isInstanceOf(BusinessException.class);
        parameters.clear();
        parameters.add("querySessionId", "session");
        assertThatThrownBy(() -> RestaurantMapPageRequest.from(parameters)).isInstanceOf(BusinessException.class);
        parameters.add("sort", "reviews");
        assertThat(RestaurantMapPageRequest.from(parameters).sort()).isEqualTo(RestaurantMapSort.REVIEWS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "9223372036854775808", "", "+1", "1.0"})
    void 지역ID_형식을_검증한다(String id) {
        var parameters = bounds();
        parameters.add("mapRegionId", id);
        assertThatThrownBy(() -> RestaurantMapPageRequest.from(parameters)).isInstanceOf(BusinessException.class);
    }

    private LinkedMultiValueMap<String, String> bounds() {
        var parameters = new LinkedMultiValueMap<String, String>();
        parameters.add("south", "0");
        parameters.add("north", "1");
        parameters.add("west", "0");
        parameters.add("east", "1");
        return parameters;
    }

    @ParameterizedTest
    @ValueSource(strings = {"1e-2147483647", "1e2147483647", "1e2147483648", "1e-129", "1e129", "NaN", "Infinity"})
    void 비정상_지수는_과대한_산술_전에_빠르게_거절한다(String south) {
        var parameters = bounds();
        parameters.set("south", south);
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertThatThrownBy(
                () -> RestaurantMapPageRequest.from(parameters)).isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(RestaurantErrorCode.MAP_BOUNDS_INVALID)));
    }

    @Test
    void 숫자길이128과_scale128까지_원문정밀도를_보존하고_초과를_거절한다() {
        var parameters = bounds();
        parameters.set("south", "0." + "1".repeat(126));
        assertThat(RestaurantMapPageRequest.from(parameters).criteria().bounds().south())
                .isEqualTo(new BigDecimal("0." + "1".repeat(126)));
        parameters.set("south", "1e-128");
        assertThat(RestaurantMapPageRequest.from(parameters).criteria().bounds().south()).isEqualTo(new BigDecimal("1e-128"));
        parameters.set("south", "0." + "1".repeat(127));
        assertThatThrownBy(() -> RestaurantMapPageRequest.from(parameters)).isInstanceOf(BusinessException.class);
    }
}
