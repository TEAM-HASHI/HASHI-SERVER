package org.sopt.hashi.user.collection.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.sopt.hashi.media.MediaImage;

/**
 * 컬렉션 저장 식당 목록 응답(SAVED-008) — 커서 페이지네이션. nextCursor는 다음 페이지 요청에 그대로 전달하며,
 * hasNext가 false면 nextCursor는 null이다. 정렬·분류 필터가 바뀌면 커서를 버리고 처음부터 조회한다.
 * 삭제된 식당은 목록에서 제외된다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SavedRestaurantListResponse(
        List<SavedRestaurantResponse> content,
        String nextCursor,
        boolean hasNext) {

    /** 저장 식당 카드 — 대표 이미지·식당명·평점·지역·음식 종류·음식점 분류와 저장 시각. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SavedRestaurantResponse(
            Long restaurantId,
            String name,
            BigDecimal rating,
            long reviewCount,
            String area,
            String foodCategory,
            String placeType,
            String thumbnailUrl,
            MediaImage thumbnailImage,
            LocalDateTime savedAt) {
    }
}
