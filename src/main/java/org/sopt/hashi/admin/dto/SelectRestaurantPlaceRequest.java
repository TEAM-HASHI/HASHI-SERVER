package org.sopt.hashi.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record SelectRestaurantPlaceRequest(
        @Schema(description = "후보 검색 응답과 같은 최신 addressRevision(위치 확인 기준 정보 변경 번호)", example = "2")
        @NotNull(message = "주소 버전은 필수입니다")
        @Positive(message = "주소 버전은 양수입니다") Long expectedAddressRevision,
        @Schema(description = "후보 검색 응답의 selectionToken. 발급받은 관리자만 10분 안에 한 번 선택 가능. 이름·주소가 바뀌거나 토큰을 수정하면 무효",
                example = "ZXlK...Q2Q")
        @NotBlank(message = "후보 선택 토큰은 필수입니다")
        @Size(max = 2048, message = "후보 선택 토큰이 너무 깁니다") String selectionToken) {
}
