package org.sopt.hashi.restaurant.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

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
}
