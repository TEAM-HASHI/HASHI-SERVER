package org.sopt.hashi.restaurant.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.List;
import org.sopt.hashi.restaurant.RestaurantImageInfo;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RestaurantListResponse(
        List<RestaurantSummaryResponse> content,
        String nextCursor,
        boolean hasNext
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RestaurantSummaryResponse(
            Long restaurantId,
            String name,
            BigDecimal rating,
            String thumbnailUrl,
            RestaurantImageInfo thumbnailImage,
            List<String> imageUrls,
            List<RestaurantImageInfo> cardImages,
            String area,
            String genre,
            String foodCategory,
            String summary,
            List<String> hashtags,
            TodayBusinessHourResponse todayBusinessHour
    ) {

        public RestaurantSummaryResponse(
                Long restaurantId,
                String name,
                BigDecimal rating,
                String thumbnailUrl,
                List<String> imageUrls,
                String area,
                String genre,
                String foodCategory,
                String summary,
                List<String> hashtags,
                TodayBusinessHourResponse todayBusinessHour
        ) {
            this(
                    restaurantId, name, rating, thumbnailUrl, null, imageUrls, List.of(),
                    area, genre, foodCategory, summary, hashtags, todayBusinessHour);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TodayBusinessHourResponse(
            String date,
            String dayOfWeek,
            String openTime,
            String closeTime,
            boolean closed
    ) {
    }
}
