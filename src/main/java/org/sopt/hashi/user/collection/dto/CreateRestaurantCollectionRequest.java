package org.sopt.hashi.user.collection.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.sopt.hashi.user.collection.domain.RestaurantCollection;

/**
 * 컬렉션 생성 요청(SAVED-006). 컬렉션명·색상·공개 범위는 필수, 설명은 선택(100자).
 * color는 "red"·"orange"·"yellow"·"green"·"blue"·"purple", visibility는 "public"·"private"다.
 * 공개 범위는 프라이버시와 직결돼 서버가 기본값을 채우지 않고 클라이언트가 선택값을 명시한다(시안 기본 선택은 공개).
 * 컬렉션명의 공백 판정은 수정 요청과 같이 유니코드 공백(전각 공백 등)까지 보고, 줄바꿈이 섞여 있어도 판정한다.
 */
public record CreateRestaurantCollectionRequest(
        @Schema(description = "컬렉션명", example = "2026 도쿄 여름 여행")
        @NotBlank(message = "컬렉션명은 필수입니다")
        @Pattern(regexp = "(?sU).*\\S.*", message = "컬렉션명은 공백일 수 없습니다")
        @Size(max = RestaurantCollection.NAME_MAX_LENGTH, message = "컬렉션명은 20자 이내입니다") String name,
        @Schema(description = "커버 색상(red·orange·yellow·green·blue·purple)", example = "red")
        @NotBlank(message = "색상은 필수입니다") String color,
        @Schema(description = "설명(선택)", example = "여행 가기 전에 저장해둔 맛집들 중에서 꼭 가보고 싶은 곳")
        @Size(max = RestaurantCollection.DESCRIPTION_MAX_LENGTH, message = "설명은 100자 이내입니다") String description,
        @Schema(description = "공개 범위(public·private)", example = "public")
        @NotBlank(message = "공개 범위는 필수입니다") String visibility) {
}
