package org.sopt.hashi.restaurant.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.sopt.hashi.restaurant.RestaurantImageInfo;
import org.sopt.hashi.restaurant.RestaurantMapInfo.LocationInfo;
import org.sopt.hashi.restaurant.domain.MapSearchCriteria;
import org.sopt.hashi.restaurant.domain.RestaurantMapCandidate;
import org.sopt.hashi.restaurant.domain.RestaurantMapSort;
import org.sopt.hashi.restaurant.dto.RestaurantListResponse.RestaurantSummaryResponse;
import org.sopt.hashi.restaurant.dto.RestaurantListResponse.TodayBusinessHourResponse;
import org.sopt.hashi.restaurant.dto.RestaurantStoreInformationResponse.PriceRangeResponse;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RestaurantMapPageResponse(List<MapCardResponse> content, String nextCursor, boolean hasNext,
                                        String querySessionId, Instant expiresAt, Instant rankingAsOf,
                                        QueryResponse query) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record QueryResponse(BigDecimal south, BigDecimal north, BigDecimal west, BigDecimal east,
                                Long mapRegionId, String keyword, String genre, String placeType, String sort) {
        public static QueryResponse from(MapSearchCriteria criteria, RestaurantMapSort sort) {
            return new QueryResponse(criteria.bounds().south(), criteria.bounds().north(),
                    criteria.bounds().west(), criteria.bounds().east(), criteria.mapRegionId(), criteria.keyword(),
                    criteria.genre() == null ? null : criteria.genre().value(),
                    criteria.placeType() == null ? null : criteria.placeType().value(), sort.value());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MapCardResponse(Long restaurantId, String name, BigDecimal rating, String thumbnailUrl,
                                  RestaurantImageInfo thumbnailImage, List<String> imageUrls,
                                  List<RestaurantImageInfo> cardImages, String area, String genre,
                                  String foodCategory, String summary, List<String> hashtags,
                                  TodayBusinessHourResponse todayBusinessHour, String placeType, long reviewCount,
                                  PriceRangeResponse priceRange, LocationInfo location) {
        public static MapCardResponse from(RestaurantSummaryResponse card, RestaurantMapCandidate rank,
                                           String placeType, PriceRangeResponse price, LocationInfo location) {
            return new MapCardResponse(card.restaurantId(), card.name(), rank.rating(), card.thumbnailUrl(),
                    card.thumbnailImage(), card.imageUrls(), card.cardImages(), card.area(), card.genre(),
                    card.foodCategory(), card.summary(), card.hashtags(), card.todayBusinessHour(),
                    placeType, rank.reviewCount(), price, location);
        }
    }
}
