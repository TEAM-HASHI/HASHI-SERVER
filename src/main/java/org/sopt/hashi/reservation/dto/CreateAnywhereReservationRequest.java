package org.sopt.hashi.reservation.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

/**
 * 어디든 예약(미등록 식당) 생성 요청. restaurant 테이블에 없는 식당이라 식당명·주소를 직접 받는다
 * (식당 존재 검증 없음). 예약자(userId)는 CurrentUserProvider에서 얻는다(auth.md §2).
 * 인원 합계는 최소 1명이어야 한다.
 */
public record CreateAnywhereReservationRequest(
        @NotBlank(message = "예약자 이름은 필수입니다") @Size(max = 50) String reserverName,
        @NotBlank(message = "식당명은 필수입니다") @Size(max = 100) String restaurantName,
        @NotBlank(message = "식당 주소는 필수입니다") @Size(max = 255) String restaurantAddress,
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
        @Size(max = 500, message = "요청사항은 500자 이내입니다") String requestNote) {

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
