package org.sopt.hashi.user.collection.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.sopt.hashi.user.collection.dto.RestaurantCollectionResponse.CoverResponse;

/**
 * 내 컬렉션 목록 응답(SAVED-008 목록·SAVED-006 저장 모달·SAVED-004 이동 모달 공용) — 생성일 최신순 커서 페이지네이션.
 * nextCursor는 다음 페이지 요청에 그대로 전달하며, hasNext가 false면 마지막 페이지라 nextCursor는 null이다.
 * totalCount는 사용자의 전체 컬렉션 수로, 목록 헤더의 "컬렉션 N개" 표시용이다(페이지에 담긴 수가 아니다).
 * restaurantId를 주고 조회하면 항목마다 그 식당이 이미 저장돼 있는지 saved를 채우고, 주지 않으면 saved를 생략한다.
 */
public record RestaurantCollectionListResponse(
        List<RestaurantCollectionSummaryResponse> collections,
        long totalCount,
        Long nextCursor,
        boolean hasNext) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RestaurantCollectionSummaryResponse(
            Long collectionId,
            String name,
            String color,
            String visibility,
            int savedCount,
            CoverResponse cover,
            Boolean saved) {
    }
}
