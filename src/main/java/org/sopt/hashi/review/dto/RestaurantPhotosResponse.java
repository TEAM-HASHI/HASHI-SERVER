package org.sopt.hashi.review.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.sopt.hashi.media.MediaImage;
import org.sopt.hashi.review.domain.RestaurantPhotoFilter;

public record RestaurantPhotosResponse(
        long restaurantId,
        RestaurantPhotoFilter filter,
        @Schema(description = "필터 칩용 개수. ALL은 같은 원본 참조를 중복 제거하므로 다른 세 값의 합과 다를 수 있습니다")
        Counts counts,
        @Schema(description = "현재 필터의 전체 사진 슬롯 수. 리뷰는 이미지 조회 결과가 없어도 포함합니다") long totalCount,
        List<Photo> photos,
        @Schema(description = "다음 요청에 그대로 보낼 cursor. 마지막은 null", nullable = true) String nextCursor,
        boolean hasNext
) {
    public record Counts(long all, long representative, long menu, long review) { }

    public record Photo(
            @Schema(description = "목록 항목 식별자. 출처와 association ID 조합", example = "REVIEW:123") String photoId,
            RestaurantPhotoFilter type,
            @Schema(description = "표시 URL. 처리 중·실패·조회 결과가 없는 asset은 null이며 기본 이미지를 표시합니다", nullable = true)
            String imageUrl,
            @Schema(description = "asset의 상태·반응형 후보. legacy 또는 이미지 조회 결과가 없으면 null", nullable = true)
            MediaImage image
    ) { }
}
