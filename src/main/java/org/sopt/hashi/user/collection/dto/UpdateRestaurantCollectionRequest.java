package org.sopt.hashi.user.collection.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.sopt.hashi.user.collection.domain.RestaurantCollection;

/**
 * 컬렉션 부분 수정(PATCH) 요청(SAVED-007) — null 필드는 변경하지 않는다.
 * 설명은 빈 문자열을 보내면 지운다. 공유 전 "공개 후 공유"도 visibility 변경으로 처리한다.
 */
public record UpdateRestaurantCollectionRequest(
        @Schema(description = "컬렉션명(선택, 공백 불가)", example = "2026 도쿄 겨울 여행")
        @Pattern(regexp = "(?sU).*\\S.*", message = "컬렉션명은 공백일 수 없습니다")
        @Size(max = RestaurantCollection.NAME_MAX_LENGTH, message = "컬렉션명은 20자 이내입니다") String name,
        @Schema(description = "커버 색상(red·orange·yellow·green·blue·purple, 선택)", example = "blue")
        @Pattern(regexp = "(?sU).*\\S.*", message = "색상은 공백일 수 없습니다") String color,
        @Schema(description = "설명(선택) — 빈 문자열이면 설명을 지운다", example = "도쿄여행 야호")
        @Size(max = RestaurantCollection.DESCRIPTION_MAX_LENGTH, message = "설명은 100자 이내입니다") String description,
        @Schema(description = "공개 범위(public·private, 선택)", example = "private")
        @Pattern(regexp = "(?sU).*\\S.*", message = "공개 범위는 공백일 수 없습니다") String visibility) {
}
