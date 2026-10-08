package org.sopt.hashi.user.collection.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** visibleRestaurantCount는 현재 존재하는 식당 수다. 좌표 없는 식당은 이 수에 포함되지만 content에는 없다. */
@Schema(description = "컬렉션 전체의 현재 유효 지도 핀. viewport·BBOX·목록 페이지·필터는 적용하지 않음")
public record CollectionMapMarkersResponse(
        @Schema(description = "컬렉션 ID", example = "42")
        Long collectionId,
        @Schema(description = "응답을 만든 컬렉션 저장 관계의 변경 번호", example = "7")
        long collectionVersion,
        @Schema(description = "핀 유효성을 판정하고 응답을 생성한 UTC 시각")
        Instant generatedAt,
        @Schema(description = "저장 관계 중 현재 존재하고 삭제되지 않은 식당 수. 좌표가 없는 식당도 포함",
                example = "12")
        int visibleRestaurantCount,
        @Schema(description = "현재 존재하지만 좌표가 없거나 generatedAt 기준 만료되어 content에서 빠진 식당 수. 저장 관계는 삭제되지 않음",
                example = "2")
        int locationUnavailableCount,
        @Schema(description = "유효한 좌표가 있는 식당 핀 전체. 식당 ID 오름차순이며 페이지네이션 없음")
        List<Marker> content) {

    public record Marker(
            @Schema(description = "식당 ID", example = "1001")
            Long restaurantId,
            @Schema(description = "식당명", example = "하시 스시")
            String name,
            @Schema(description = "음식점 분류", example = "restaurant")
            String placeType,
            @Schema(description = "장르", example = "sushi")
            String genre,
            @Schema(description = "현재 유효한 지도 위치")
            Position location) { }

    public record Position(
            @Schema(description = "위도", example = "35.681236")
            BigDecimal latitude,
            @Schema(description = "경도", example = "139.767125")
            BigDecimal longitude,
            @Schema(description = "좌표 유효 기한(UTC). generatedAt보다 늦은 핀만 응답")
            Instant validUntil) { }
}
