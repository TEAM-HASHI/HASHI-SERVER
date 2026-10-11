package org.sopt.hashi.user.collection.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.sopt.hashi.user.collection.domain.RestaurantCollection;

/**
 * 컬렉션 부분 수정(PATCH) 요청(SAVED-007) — null 필드는 변경하지 않는다.
 * 이름·설명은 앞뒤 공백을 제거하고, 설명은 빈 문자열이나 공백만 보내면 지운다.
 * 공유 전 "공개 후 공유"도 visibility 변경으로 처리한다.
 */
public record UpdateRestaurantCollectionRequest(
        @Schema(description = "컬렉션명(선택). 생략·null이면 유지합니다. 입력값은 공백을 포함해 최대 20자이며, "
                + "앞뒤 공백을 제거한 이름으로 저장하고 중복을 확인합니다. 중간 공백은 유지하며 공백만 있는 이름은 거부합니다.",
                example = "2026 도쿄 겨울 여행")
        @Pattern(regexp = "(?sU).*\\S.*", message = "컬렉션명은 공백일 수 없습니다")
        @Size(max = RestaurantCollection.NAME_MAX_LENGTH, message = "컬렉션명은 20자 이내입니다") String name,
        @Schema(description = "커버 색상(red·orange·yellow·green·blue·purple, 선택)", example = "blue")
        @Pattern(regexp = "(?sU).*\\S.*", message = "색상은 공백일 수 없습니다") String color,
        @Schema(description = "설명(선택). 생략·null이면 유지하고 빈 문자열·공백만 입력하면 삭제합니다. "
                + "입력값은 공백을 포함해 최대 100자이며 앞뒤 공백은 제거하고 중간 공백은 유지합니다.", example = "도쿄여행 야호")
        @Size(max = RestaurantCollection.DESCRIPTION_MAX_LENGTH, message = "설명은 100자 이내입니다") String description,
        @Schema(description = "공개 범위(public·private, 선택)", example = "private")
        @Pattern(regexp = "(?sU).*\\S.*", message = "공개 범위는 공백일 수 없습니다") String visibility) {
}
