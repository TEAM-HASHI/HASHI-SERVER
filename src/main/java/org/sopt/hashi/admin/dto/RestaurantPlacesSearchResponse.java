package org.sopt.hashi.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.sopt.hashi.restaurant.RestaurantPlacesCandidateInfo;
import org.sopt.hashi.restaurant.RestaurantPlacesSearchInfo;

@Schema(description = "저장된 식당명과 위치 확인 주소로 조회한 Google Places 후보")
public record RestaurantPlacesSearchResponse(
        @Schema(example = "1001") Long restaurantId,
        @Schema(description = "검색 시점의 주소 revision", example = "2") long addressRevision,
        @Schema(description = "도쿄 서비스 범위를 통과한 후보. 검색 결과가 없으면 빈 배열")
        List<Candidate> candidates) {

    public static RestaurantPlacesSearchResponse from(RestaurantPlacesSearchInfo info) {
        return new RestaurantPlacesSearchResponse(info.restaurantId(), info.addressRevision(),
                info.candidates().stream().map(Candidate::from).toList());
    }

    public record Candidate(
            @Schema(example = "焼肉 力丸 池袋東口店") String displayName,
            @Schema(example = "1 Chome-1-1 Higashiikebukuro, Toshima City, Tokyo") String address,
            @Schema(example = "35.729503") BigDecimal latitude,
            @Schema(example = "139.710900") BigDecimal longitude,
            @Schema(example = "JP") String countryCode,
            @Schema(example = "Tokyo") String administrativeArea,
            @Schema(example = "[\"restaurant\",\"food\"]") List<String> types,
            @Schema(example = "OPERATIONAL") String businessStatus,
            List<Attribution> attributions,
            @Schema(example = "https://maps.google.com/?cid=123") String googleMapsUri,
            @Schema(description = "이 후보와 restaurant/revision/request를 묶은 10분 유효 서명 토큰")
            String selectionToken,
            @Schema(description = "selectionToken 만료 시각(UTC)") Instant selectionExpiresAt) {

        static Candidate from(RestaurantPlacesCandidateInfo info) {
            return new Candidate(info.displayName(), info.address(), info.latitude(), info.longitude(),
                    info.countryCode(), info.administrativeArea(), info.types(), info.businessStatus(),
                    info.attributions().stream().map(Attribution::from).toList(), info.googleMapsUri(),
                    info.selectionToken(), info.selectionExpiresAt());
        }
    }

    public record Attribution(@Schema(example = "Google") String displayName,
                              @Schema(example = "https://www.google.com") String uri) {
        static Attribution from(RestaurantPlacesCandidateInfo.Attribution attribution) {
            return new Attribution(attribution.displayName(), attribution.uri());
        }
    }
}
