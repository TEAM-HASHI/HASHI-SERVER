package org.sopt.hashi.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.sopt.hashi.restaurant.RestaurantPlacesCandidateInfo;

@Schema(description = "아직 식당을 저장하지 않은 Places 미리보기. 후보를 선택해 locationSelectionToken으로 등록 요청에 전달")
public record RestaurantLocationPreviewResponse(
        @Schema(description = "서비스 범위를 통과한 후보. 빈 배열은 정상 검색 결과 0건이며 오류와 다름")
        List<RestaurantPlacesSearchResponse.Candidate> candidates) {
    public static RestaurantLocationPreviewResponse from(List<RestaurantPlacesCandidateInfo> candidates) {
        return new RestaurantLocationPreviewResponse(candidates.stream()
                .map(RestaurantPlacesSearchResponse.Candidate::from).toList());
    }
}
