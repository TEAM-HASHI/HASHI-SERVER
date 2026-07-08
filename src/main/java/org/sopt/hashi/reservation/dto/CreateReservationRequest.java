package org.sopt.hashi.reservation.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import org.sopt.hashi.reservation.domain.Reservation;

/**
 * 예약 생성 요청. 예약자(userId)는 요청 값을 신뢰하지 않고 CurrentUserProvider에서 얻으므로 여기 없다(auth.md §2).
 * 인원은 성인·청소년·아동으로 구분하며 합계는 최소 1명이어야 한다.
 */
public record CreateReservationRequest(
        @NotBlank(message = "예약자 이름은 필수입니다") @Size(max = 50) String reserverName,
        @NotNull(message = "식당 ID는 필수입니다")
        @Positive(message = "식당 ID는 양수여야 합니다") Long restaurantId,
        @NotNull(message = "예약 일시는 필수입니다")
        @Future(message = "예약 일시는 미래여야 합니다") LocalDateTime reservedAt,
        @NotNull(message = "성인 인원은 필수입니다")
        @Min(value = 0, message = "인원은 0명 이상입니다")
        @Max(value = 100, message = "인원은 최대 100명입니다") Integer adultCount,
        @NotNull(message = "청소년 인원은 필수입니다")
        @Min(value = 0, message = "인원은 0명 이상입니다")
        @Max(value = 100, message = "인원은 최대 100명입니다") Integer teenCount,
        @NotNull(message = "아동 인원은 필수입니다")
        @Min(value = 0, message = "인원은 0명 이상입니다")
        @Max(value = 100, message = "인원은 최대 100명입니다") Integer childCount,
        @Size(max = 500, message = "요청사항은 500자 이내입니다") String requestNote,
        @Min(value = 0, message = "사용 포인트는 0 이상이어야 합니다")
        @Max(value = Reservation.BASE_FEE, message = "사용 포인트는 결제 수수료를 초과할 수 없습니다") Long usedPoint,
        @NotNull(message = "최종 결제 금액은 필수입니다")
        @Min(value = 0, message = "결제 금액은 0 이상이어야 합니다")
        @Max(value = Reservation.BASE_FEE, message = "결제 금액은 기본 수수료를 초과할 수 없습니다") Long amount) {

    /** 인원 합계(성인+청소년+아동)는 최소 1명이어야 한다. 개별 null은 각 필드의 @NotNull이 처리한다. */
    @JsonIgnore
    @AssertTrue(message = "예약 인원은 최소 1명 이상이어야 합니다")
    public boolean isPartyCountValid() {
        if (adultCount == null || teenCount == null || childCount == null) {
            return true;
        }
        return adultCount + teenCount + childCount >= 1;
    }
}
