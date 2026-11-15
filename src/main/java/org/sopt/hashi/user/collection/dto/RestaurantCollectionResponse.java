package org.sopt.hashi.user.collection.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 컬렉션 단건 응답(생성·수정 결과, 상세 헤더). savedCount는 삭제된 식당을 뺀 현재 표시 가능한 저장 식당 수다.
 * cover는 컬렉션 색상과 저장이 오래된 순 대표 이미지 최대 3장이며, 이미지가 없는 식당은 건너뛴다.
 * owner는 현재 사용자가 소유자인지(비로그인은 false)로, 클라이언트가 편집·삭제 메뉴 노출을 결정한다.
 */
public record RestaurantCollectionResponse(
        Long collectionId,
        String name,
        String color,
        String description,
        String visibility,
        int savedCount,
        CoverResponse cover,
        boolean owner,
        LocalDateTime createdAt) {

    /** 커버 — 시계 방향 1번 칸은 color, 2~4번 칸은 imageUrls 순서대로 채운다(부족하면 있는 만큼). */
    public record CoverResponse(
            String color,
            List<String> imageUrls) {
    }
}
