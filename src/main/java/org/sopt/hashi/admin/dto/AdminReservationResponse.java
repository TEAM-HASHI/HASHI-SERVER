package org.sopt.hashi.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import org.sopt.hashi.media.MediaImage;
import org.sopt.hashi.reservation.AdminReservationInfo;
import org.sopt.hashi.reservation.PaymentStatus;
import org.sopt.hashi.reservation.ReservationStatus;
import org.sopt.hashi.reservation.ReservationType;

/**
 * 어드민 예약 단건 응답(목록 항목·상태 변경 결과). 사용자향 예약 응답 필드에 관리용
 * 예약자 ID·결제 정보(usedPoint·amount·paymentStatus)를 더한 구성이다(클라 합의 계약).
 */
public record AdminReservationResponse(
        Long reservationId,
        Long userId,
        ReservationType reservationType,
        String reserverName,
        Long restaurantId,
        String restaurantName,
        String restaurantImageUrl,
        MediaImage restaurantThumbnailImage,
        String restaurantAddress,
        LocalDateTime reservedAt,
        int adultCount,
        int teenCount,
        int childCount,
        String requestNote,
        ReservationStatus reservationStatus,
        PaymentStatus paymentStatus,
        long usedPoint,
        BigDecimal amount,
        Long confirmDDay,
        Instant paymentWaitStartedAt,
        @Schema(description = "최초 입금 대기 전환 후 24시간이 되는 고객 안내 시각. 경과 후에도 입금 확인이 가능합니다.")
        Instant paymentDeadline) {

    public static AdminReservationResponse from(
            AdminReservationInfo info,
            String restaurantImageUrl,
            MediaImage restaurantThumbnailImage
    ) {
        return new AdminReservationResponse(
                info.reservationId(),
                info.userId(),
                info.reservationType(),
                info.reserverName(),
                info.restaurantId(),
                info.restaurantName(),
                restaurantImageUrl,
                restaurantThumbnailImage,
                info.restaurantAddress(),
                info.reservedAt(),
                info.adultCount(),
                info.teenCount(),
                info.childCount(),
                info.requestNote(),
                info.reservationStatus(),
                info.paymentStatus(),
                info.usedPoint(),
                info.amount(),
                info.confirmDDay(),
                info.paymentWaitStartedAt(),
                info.paymentDeadline());
    }
}
