package org.sopt.hashi.restaurant.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.restaurant.dto.RestaurantListResponse.RestaurantSummaryResponse;
import org.sopt.hashi.restaurant.dto.RestaurantListResponse.TodayBusinessHourResponse;

class RestaurantListResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void 마지막_페이지에서는_nextCursor를_응답에서_생략한다() throws Exception {
        RestaurantListResponse response = new RestaurantListResponse(List.of(), null, false);

        String json = objectMapper.writeValueAsString(response);

        assertThat(json)
                .doesNotContain("nextCursor")
                .contains("\"content\":[]", "\"hasNext\":false");
    }

    @Test
    void 다음_페이지가_있으면_nextCursor를_응답에_포함한다() throws Exception {
        RestaurantListResponse response = new RestaurantListResponse(List.of(), "cursor-value", true);

        String json = objectMapper.writeValueAsString(response);

        assertThat(json)
                .contains("\"nextCursor\":\"cursor-value\"", "\"hasNext\":true");
    }

    @Test
    void 영업시간_정보가_없으면_todayBusinessHour를_응답에서_생략한다() throws Exception {
        RestaurantListResponse response = new RestaurantListResponse(
                List.of(createSummary(null)),
                null,
                false
        );

        String json = objectMapper.writeValueAsString(response);

        assertThat(json).doesNotContain("todayBusinessHour");
    }

    @Test
    void 정기_휴무이면_영업_시각을_생략하고_closed를_반환한다() throws Exception {
        TodayBusinessHourResponse businessHour = new TodayBusinessHourResponse(
                "2026-07-13",
                "MONDAY",
                null,
                null,
                true
        );
        RestaurantListResponse response = new RestaurantListResponse(
                List.of(createSummary(businessHour)),
                null,
                false
        );

        String json = objectMapper.writeValueAsString(response);

        assertThat(json)
                .contains("\"todayBusinessHour\"", "\"date\":\"2026-07-13\"", "\"closed\":true")
                .doesNotContain("openTime", "closeTime");
    }

    private RestaurantSummaryResponse createSummary(TodayBusinessHourResponse businessHour) {
        return new RestaurantSummaryResponse(
                1L,
                "히마와리 스시",
                BigDecimal.valueOf(4.8),
                "https://cdn.example.com/thumbnail.jpg",
                List.of("https://cdn.example.com/image.jpg"),
                "도쿄",
                "스시/사시미류",
                "초밥",
                "제철 생선을 사용하는 스시 전문점",
                List.of("오마카세"),
                businessHour
        );
    }
}
