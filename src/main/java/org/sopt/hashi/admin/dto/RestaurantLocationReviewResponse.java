package org.sopt.hashi.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.sopt.hashi.restaurant.RestaurantLocationReviewInfo;

@Schema(description = "관리자 위치 검토 목록의 식당. 좌표와 내부 작업 식별자는 노출하지 않음")
public record RestaurantLocationReviewResponse(
        @Schema(description = "식당 ID", example = "1001")
        Long restaurantId,
        @Schema(description = "식당명", example = "야키니쿠 리키마루 이케부쿠로점")
        String name,
        @Schema(description = "화면에 표시하는 전체 주소", example = "東京都豊島区東池袋1-1-1 架空ビル1F")
        String address,
        @Schema(description = "Google 위치 확인에만 쓰는 별도 지정 주소. null이면 address를 사용",
                example = "東京都豊島区東池袋1-1-1", nullable = true)
        String geocodingAddress,
        @Schema(description = "위치 처리 상태",
                allowableValues = {"UNRESOLVED", "PENDING", "READY", "RETRY_WAIT", "REVIEW_REQUIRED", "FAILED"},
                example = "REVIEW_REQUIRED")
        String locationStatus,
        @Schema(description = "마지막 승인 위치의 출처. 승인 위치가 없으면 null",
                allowableValues = {"GOOGLE_GEOCODING", "GOOGLE_PLACES", "ADMIN"}, example = "GOOGLE_PLACES", nullable = true)
        String source,
        @Schema(description = "현재 requestId 작업의 검증 방식",
                allowableValues = {"GEOCODING", "PLACE_DETAILS"}, example = "PLACE_DETAILS", nullable = true)
        String verificationMode,
        @Schema(description = "현재 위치 확인 기준 주소의 revision. 재시도 요청의 expectedAddressRevision으로 사용",
                example = "2")
        long addressRevision,
        @Schema(description = "승인 좌표의 유효 기한(UTC). 승인 좌표가 없으면 null", nullable = true)
        Instant validUntil,
        @Schema(description = "현재 requestId 작업의 provider 호출 시도 횟수", example = "1")
        int attempt,
        @Schema(description = "다음 자동 재시도 예정 시각(UTC). 해당하지 않으면 null", nullable = true)
        Instant nextAttemptAt,
        @Schema(description = "현재 requestId 작업의 최근 실패 사유 코드. 없으면 null",
                example = "ZERO_RESULTS", nullable = true)
        String failureCode,
        @Schema(description = "관리자 재시도 가능 여부", example = "true")
        boolean canRetry) {

    public static RestaurantLocationReviewResponse from(RestaurantLocationReviewInfo info) {
        return new RestaurantLocationReviewResponse(
                info.restaurantId(), info.name(), info.address(), info.geocodingAddress(),
                info.locationStatus(), info.source(), info.verificationMode(), info.addressRevision(), info.validUntil(),
                info.attempt(), info.nextAttemptAt(), info.failureCode(), info.canRetry());
    }
}
