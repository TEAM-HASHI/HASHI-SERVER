package org.sopt.hashi.restaurant.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.sopt.hashi.restaurant.RestaurantImageInfo;
import org.sopt.hashi.restaurant.RestaurantMapInfo.LocationInfo;
import org.sopt.hashi.restaurant.domain.MapSearchCriteria;
import org.sopt.hashi.restaurant.domain.MapSearchResultExtent;
import org.sopt.hashi.restaurant.domain.MapSearchResultExtent.ResultBounds;
import org.sopt.hashi.restaurant.domain.RestaurantMapCandidate;
import org.sopt.hashi.restaurant.domain.RestaurantMapSort;
import org.sopt.hashi.restaurant.dto.RestaurantListResponse.RestaurantSummaryResponse;
import org.sopt.hashi.restaurant.dto.RestaurantListResponse.TodayBusinessHourResponse;
import org.sopt.hashi.restaurant.dto.RestaurantStoreInformationResponse.PriceRangeResponse;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "지도 목록 한 페이지와 조회 세션 정보")
public record RestaurantMapPageResponse(
        @Schema(description = "현재 페이지의 식당 카드 겸 핀 데이터. 유효한 결과가 없어도 빈 배열이며 최대 10개")
        List<MapCardResponse> content,
        @Schema(description = "다음 페이지에 그대로 전달할 72자 cursor. hasNext=false이면 응답에서 생략",
                example = "AhI-RWfomxLTpFZCZhQXQAAAAAAACso6YZT4UYYs1U4g1RUyPn-6Z318IrJMCmUuvhgmPhvW")
        String nextCursor,
        @Schema(description = "다음 유효 후보 페이지 존재 여부", example = "true")
        boolean hasNext,
        @Schema(description = "정렬 변경에 사용하는 조회 세션 ID", format = "uuid",
                example = "123e4567-e89b-12d3-a456-426614174000")
        String querySessionId,
        @Schema(description = "이번 정상 조회 뒤 확정된 서버 세션 만료 시각. 유휴 5분 갱신, 최초 생성부터 최대 30분이며 "
                + "검색 결과 좌표 중 가장 이른 만료 시각을 넘지 않음",
                example = "2026-09-26T01:05:00Z")
        Instant expiresAt,
        @Schema(description = "별점·리뷰 수와 검색 결과 요약을 고정한 시각", example = "2026-09-26T01:00:00Z")
        Instant rankingAsOf,
        @Schema(description = "keyword 조회에서만 제공하는 최초 검색 결과 전체의 수와 경계. "
                + "rankingAsOf 이후 식당 삭제 등으로 현재 content와 달라질 수 있음")
        SearchResultResponse searchResult,
        @Schema(description = "세션에 고정되어 실제로 적용된 조회 범위·필터·정렬")
        QueryResponse query) {

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(description = "검색 결과 전체를 화면에 맞추기 위한 최초 조회 시점의 집계")
    public record SearchResultResponse(
            @Schema(description = "현재 viewport와 keyword 조건에 최초 일치한 전체 식당 수", example = "27")
            long totalCount,
            @Schema(description = "전체 검색 결과 좌표의 최소 경계. 0건이면 null이고 1건이면 각 최소·최대값이 같음",
                    nullable = true)
            ResultBoundsResponse bounds) {

        public static SearchResultResponse from(MapSearchResultExtent extent) {
            return extent == null ? null : new SearchResultResponse(extent.totalCount(),
                    ResultBoundsResponse.from(extent.bounds()));
        }
    }

    @Schema(description = "검색 결과 전체의 좌표 경계")
    public record ResultBoundsResponse(
            @Schema(description = "최소 위도", example = "35.0") BigDecimal south,
            @Schema(description = "최대 위도", example = "35.1") BigDecimal north,
            @Schema(description = "최소 경도", example = "139.0") BigDecimal west,
            @Schema(description = "최대 경도", example = "139.1") BigDecimal east) {

        private static ResultBoundsResponse from(ResultBounds bounds) {
            return bounds == null ? null : new ResultBoundsResponse(
                    bounds.south(), bounds.north(), bounds.west(), bounds.east());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "지도 조회에 적용된 정규화 조건")
    public record QueryResponse(
            @Schema(description = "조회 범위의 남쪽 위도", example = "35.0") BigDecimal south,
            @Schema(description = "조회 범위의 북쪽 위도", example = "35.1") BigDecimal north,
            @Schema(description = "조회 범위의 서쪽 경도", example = "139.0") BigDecimal west,
            @Schema(description = "조회 범위의 동쪽 경도", example = "139.1") BigDecimal east,
            @Schema(description = "적용된 관광 지역 ID. 지역 필터가 없으면 생략", example = "1") Long mapRegionId,
            @Schema(description = "앞뒤 공백이 제거된 검색어. 검색어가 없으면 생략", example = "sushi") String keyword,
            @Schema(description = "적용된 음식 장르. 필터가 없으면 생략", example = "sushi") String genre,
            @Schema(description = "적용된 음식점 분류. 전체이면 생략", example = "restaurant") String placeType,
            @Schema(description = "적용된 지도 정렬", allowableValues = {"recommend", "rating", "reviews"},
                    example = "recommend") String sort) {
        public static QueryResponse from(MapSearchCriteria criteria, RestaurantMapSort sort) {
            return new QueryResponse(criteria.bounds().south(), criteria.bounds().north(),
                    criteria.bounds().west(), criteria.bounds().east(), criteria.mapRegionId(), criteria.keyword(),
                    criteria.genre() == null ? null : criteria.genre().value(),
                    criteria.placeType() == null ? null : criteria.placeType().value(), sort.value());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "목록 카드와 지도 핀에 함께 사용하는 식당 정보")
    public record MapCardResponse(
            @Schema(description = "식당 ID", example = "1") Long restaurantId,
            @Schema(description = "식당명", example = "스시 하시") String name,
            @Schema(description = "rankingAsOf 기준 별점", example = "4.5") BigDecimal rating,
            String thumbnailUrl,
            RestaurantImageInfo thumbnailImage, List<String> imageUrls,
            List<RestaurantImageInfo> cardImages, String area, String genre,
            String foodCategory, String summary, List<String> hashtags,
            TodayBusinessHourResponse todayBusinessHour,
            @Schema(description = "음식점 분류", allowableValues = {"restaurant", "cafe", "bar"})
            String placeType,
            @Schema(description = "rankingAsOf 기준 리뷰 수", example = "128") long reviewCount,
            PriceRangeResponse priceRange,
            @Schema(description = "핀 좌표와 좌표 만료 시각. content 항목에는 현재 유효한 위치만 포함")
            LocationInfo location) {
        public static MapCardResponse from(RestaurantSummaryResponse card, RestaurantMapCandidate rank,
                                           String placeType, PriceRangeResponse price, LocationInfo location) {
            return new MapCardResponse(card.restaurantId(), card.name(), rank.rating(), card.thumbnailUrl(),
                    card.thumbnailImage(), card.imageUrls(), card.cardImages(), card.area(), card.genre(),
                    card.foodCategory(), card.summary(), card.hashtags(), card.todayBusinessHour(),
                    placeType, rank.reviewCount(), price, location);
        }
    }
}
