package org.sopt.hashi.reservation.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import org.sopt.hashi.reservation.domain.Reservation;

/** 어디든 예약(미등록 식당) 생성 요청 — 식당명·주소를 직접 입력한다. 인원 합계는 최소 1명이어야 한다. */
public record CreateAnywhereReservationRequest(
        @Schema(description = "예약자 이름(식당이 호명할 이름)", example = "김하람")
        @NotBlank(message = "예약자 이름은 필수입니다") @Size(max = 50) String reserverName,
        @Schema(description = "식당명(미등록 식당, 직접 입력)", example = "동네 골목 이자카야")
        @NotBlank(message = "식당명은 필수입니다") @Size(max = 100) String restaurantName,
        @Schema(description = "식당 주소(직접 입력)", example = "도쿄도 도시마구 히가시이케부쿠로 1-1-1")
        @NotBlank(message = "식당 주소는 필수입니다") @Size(max = 255) String restaurantAddress,
        @Schema(description = "예약 일시(미래 시각)", example = "2030-08-01T19:00:00")
        @NotNull(message = "예약 일시는 필수입니다")
        @Future(message = "예약 일시는 미래여야 합니다") LocalDateTime reservedAt,
        @Schema(description = "성인 인원", example = "2")
        @NotNull(message = "성인 인원은 필수입니다")
        @Min(value = 0, message = "인원은 0명 이상입니다")
        @Max(value = 100, message = "인원은 최대 100명입니다") Integer adultCount,
        @Schema(description = "청소년 인원", example = "0")
        @NotNull(message = "청소년 인원은 필수입니다")
        @Min(value = 0, message = "인원은 0명 이상입니다")
        @Max(value = 100, message = "인원은 최대 100명입니다") Integer teenCount,
        @Schema(description = "아동 인원", example = "0")
        @NotNull(message = "아동 인원은 필수입니다")
        @Min(value = 0, message = "인원은 0명 이상입니다")
        @Max(value = 100, message = "인원은 최대 100명입니다") Integer childCount,
        @Schema(description = "요청사항(선택)", example = "창가 자리 부탁드립니다")
        @Size(max = 500, message = "요청사항은 500자 이내입니다") String requestNote,
        @Schema(description = "사용 포인트(선택, 미전송 시 0)", example = "0")
        @Min(value = 0, message = "사용 포인트는 0 이상이어야 합니다")
        @Max(value = Reservation.BASE_FEE, message = "사용 포인트는 결제 수수료를 초과할 수 없습니다") Long usedPoint,
        @Schema(description = "최종 결제 금액 — 기본 수수료(4,000) − usedPoint와 일치해야 함", example = "4000")
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
