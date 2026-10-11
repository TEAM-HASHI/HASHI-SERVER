package org.sopt.hashi.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.sopt.hashi.restaurant.RestaurantLocationInfo;

@Schema(description = "식당 저장과 별도로 진행되는 지도 위치 확인 상태. 좌표나 내부 작업 식별자는 노출하지 않음")
public record RestaurantLocationResponse(
        @Schema(description = "식당 ID", example = "1001")
        Long restaurantId,
        @Schema(description = "위치 처리 상태: UNRESOLVED(위치 행 없음), PENDING(처리 중), READY(위치 확인 완료), "
                + "RETRY_WAIT(자동 재시도 대기), REVIEW_REQUIRED(관리자 확인 필요), FAILED(자동 처리 중단)",
                allowableValues = {"UNRESOLVED", "PENDING", "READY", "RETRY_WAIT", "REVIEW_REQUIRED", "FAILED"},
                example = "PENDING")
        String locationStatus,
        @Schema(description = "위치 확인 기준 정보 변경 번호(addressRevision, 필드명은 호환 유지). 이름·현지명·표시주소·위치조회주소가 바뀔 때 증가하며 재시도 요청의 expectedAddressRevision으로 사용",
                example = "1")
        long addressRevision,
        @Schema(description = "마지막 승인 위치의 출처. 승인 위치가 없으면 null",
                allowableValues = {"GOOGLE_GEOCODING", "GOOGLE_PLACES", "ADMIN"}, nullable = true)
        String source,
        @Schema(description = "현재 requestId 작업의 검증 방식. 작업이 없으면 null",
                allowableValues = {"GEOCODING", "PLACE_DETAILS"}, nullable = true)
        String verificationMode,
        @Schema(description = "마지막으로 승인된 좌표의 유효 기한(UTC). 현재 시각과 같거나 과거면 READY여도 지도에서 사용할 수 없으며, 승인 좌표가 없으면 null")
        Instant validUntil,
        @Schema(description = "현재 위치 작업의 provider 호출 시도 횟수. 아직 호출하지 않았거나 작업이 없으면 0",
                example = "0")
        int attempt,
        @Schema(description = "RETRY_WAIT 상태의 다음 자동 재시도 예정 시각(UTC). 해당하지 않으면 null")
        Instant nextAttemptAt,
        @Schema(description = "현재 작업의 최근 실패 사유 코드. 실패 기록이 없으면 null",
                example = "TRANSIENT_ERROR")
        String failureCode,
        @Schema(description = "관리자 재시도 버튼 노출 여부. UNRESOLVED·RETRY_WAIT·REVIEW_REQUIRED·FAILED는 true, PENDING·READY는 false",
                example = "false")
        boolean canRetry,
        @Schema(description = "확인 방식. AUTOMATIC은 자동 검증, ADMIN_PLACE_SELECTION은 관리자의 매장 후보 선택. 과거 기록이 없으면 null")
        String confirmationMethod,
        @Schema(description = "후보를 선택한 관리자 ID. 서버 인증 정보에서 기록하며 자동 검증 또는 과거 기록이 없으면 null")
        Long confirmedByAdminId,
        @Schema(description = "위치 확인 시각(UTC). 관리자 선택은 선택 접수 시각이며 좌표 취득 완료 시각과 다름. 이 값만으로 핀 노출을 판단하지 않음. 새 후보는 READY 완료 후 표시하며 같은 매장 갱신 중의 기존 유효 핀은 유지")
        Instant confirmedAt,
        @Schema(description = "확인 당시 식당 정보 revision. 이름·주소가 바뀌면 이전 확인 기록을 초기화")
        Long confirmedRevision) {
    public static RestaurantLocationResponse from(RestaurantLocationInfo info) {
        return new RestaurantLocationResponse(info.restaurantId(), info.locationStatus(), info.addressRevision(),
                info.source(), info.verificationMode(), info.validUntil(), info.attempt(), info.nextAttemptAt(),
                info.failureCode(), info.canRetry(), info.confirmationMethod(), info.confirmedByAdminId(),
                info.confirmedAt(), info.confirmedRevision());
    }
}
