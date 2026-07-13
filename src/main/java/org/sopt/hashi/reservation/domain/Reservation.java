package org.sopt.hashi.reservation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.sopt.hashi.BaseTimeEntity;
import org.sopt.hashi.reservation.PaymentStatus;
import org.sopt.hashi.reservation.ReservationStatus;
import org.sopt.hashi.reservation.ReservationType;
import org.sopt.hashi.reservation.code.ReservationErrorCode;
import org.sopt.hashi.shared.error.BusinessException;

/**
 * 예약 애그리거트 루트. 예약자(userId)·대상 식당(restaurantId)은 타 모듈 소유이므로 FK·연관관계 없이
 * Long ID 값으로만 보관한다(§5).
 *
 * <p>유형(STANDARD/ANYWHERE)에 따라 식당 참조 방식이 다르다. STANDARD는 {@code restaurantId}로 참조하고
 * 식당명은 RestaurantPort로 조회한다. ANYWHERE는 미등록 식당이라 {@code restaurantName}·{@code restaurantAddress}를
 * 직접 보관하고 {@code restaurantId}는 null이다.
 *
 * <p>결제: 최종 수수료(amount)는 클라이언트가 계산해 보내되, 도메인 규칙({@code 기본 수수료(4,000) − usedPoint})과
 * 일치하는지 생성 시 검증한다. 결제 상태는 PENDING으로 시작하며, 포인트 차감/복원 자체는 point 모듈 소관(서비스가 PointPort로 호출).
 */
@Getter
@Entity
@Table(name = "reservation")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reservation extends BaseTimeEntity {

    /** 예약 기본 결제 수수료(원) — 도메인 규칙(2026-07 확정). 사용 포인트 상한이기도 하다. */
    public static final long BASE_FEE = 4_000L;

    /** 예약 접수 후 확정 예정까지의 고정 리드타임(일) — 도메인 규칙. */
    private static final long CONFIRM_LEAD_DAYS = 2;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 예약 당사자 이름(식당이 호명할 이름). 로그인 계정 닉네임과 별개로 예약마다 받는다. */
    @Column(name = "reserver_name", length = 50, nullable = false)
    private String reserverName;

    @Enumerated(EnumType.STRING)
    @Column(name = "reservation_type", length = 20, nullable = false)
    private ReservationType reservationType;

    /** 등록 식당(STANDARD) 참조. ANYWHERE면 null. */
    @Column(name = "restaurant_id")
    private Long restaurantId;

    /** 미등록 식당(ANYWHERE)의 식당명. STANDARD면 null(식당명은 RestaurantPort로 enrich). */
    @Column(name = "restaurant_name", length = 100)
    private String restaurantName;

    /** 미등록 식당(ANYWHERE)의 주소. STANDARD면 null. */
    @Column(name = "restaurant_address", length = 255)
    private String restaurantAddress;

    /** 예약 일시(방문 예정). DB에는 DATETIME으로 저장하고 응답은 ISO-8601로 내린다(coding-style §4-2). */
    @Column(name = "reserved_at", nullable = false)
    private LocalDateTime reservedAt;

    @Column(name = "adult_count", nullable = false)
    private int adultCount;

    @Column(name = "teen_count", nullable = false)
    private int teenCount;

    @Column(name = "child_count", nullable = false)
    private int childCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "reservation_status", length = 20, nullable = false)
    private ReservationStatus reservationStatus;

    @Column(name = "request_note", length = 1000)
    private String requestNote;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", length = 20, nullable = false)
    private PaymentStatus paymentStatus;

    /** 결제 금액 — 기본 수수료(4,000) − 사용 포인트. 생성 시점에 확정된다. */
    @Column(name = "amount", precision = 15, scale = 2, nullable = false)
    private BigDecimal amount;

    @Column(name = "used_point", nullable = false)
    private long usedPoint;

    private Reservation(ReservationType reservationType, Long userId, String reserverName,
                        Long restaurantId, String restaurantName, String restaurantAddress,
                        LocalDateTime reservedAt, int adultCount, int teenCount, int childCount,
                        String requestNote, long usedPoint, long amount) {
        validateUsedPoint(usedPoint);
        validateAmount(amount, usedPoint);
        this.reservationType = reservationType;
        this.userId = userId;
        this.reserverName = reserverName;
        this.restaurantId = restaurantId;
        this.restaurantName = restaurantName;
        this.restaurantAddress = restaurantAddress;
        this.reservedAt = reservedAt;
        this.adultCount = adultCount;
        this.teenCount = teenCount;
        this.childCount = childCount;
        this.requestNote = requestNote;
        this.reservationStatus = ReservationStatus.REQUESTED;
        this.usedPoint = usedPoint;
        this.amount = BigDecimal.valueOf(amount);
        this.paymentStatus = PaymentStatus.PENDING;
    }

    /** 등록 식당 예약을 생성한다(초기 상태 REQUESTED). 식당 존재 검증은 호출 측 서비스가 포트로 수행한다. */
    public static Reservation standard(Long userId, String reserverName, Long restaurantId,
                                       LocalDateTime reservedAt,
                                       int adultCount, int teenCount, int childCount, String requestNote,
                                       long usedPoint, long amount) {
        return new Reservation(ReservationType.STANDARD, userId, reserverName,
                restaurantId, null, null,
                reservedAt, adultCount, teenCount, childCount, requestNote, usedPoint, amount);
    }

    /** 미등록 식당(어디든) 예약을 생성한다. 식당명·주소를 직접 받아 보관한다. */
    public static Reservation anywhere(Long userId, String reserverName,
                                       String restaurantName, String restaurantAddress,
                                       LocalDateTime reservedAt,
                                       int adultCount, int teenCount, int childCount, String requestNote,
                                       long usedPoint, long amount) {
        return new Reservation(ReservationType.ANYWHERE, userId, reserverName,
                null, restaurantName, restaurantAddress,
                reservedAt, adultCount, teenCount, childCount, requestNote, usedPoint, amount);
    }

    /** 사용 포인트는 0 이상, 결제 수수료 이하(결제 금액 음수 불가) — 요청 검증의 도메인 측 방어선. */
    private static void validateUsedPoint(long usedPoint) {
        if (usedPoint < 0 || usedPoint > BASE_FEE) {
            throw new BusinessException(ReservationErrorCode.USED_POINT_EXCEEDS_FEE);
        }
    }

    /** 클라가 계산해 보낸 최종 수수료는 도메인 규칙(기본 수수료 − 사용 포인트)과 일치해야 한다(돈 값 위변조·계산 실수 방어). */
    private static void validateAmount(long amount, long usedPoint) {
        if (amount != BASE_FEE - usedPoint) {
            throw new BusinessException(ReservationErrorCode.AMOUNT_MISMATCH);
        }
    }

    /** 주어진 사용자가 이 예약의 소유자인지 확인한다(본인 리소스 접근 검증용). */
    public boolean ownedBy(Long userId) {
        return this.userId.equals(userId);
    }

    /** 예약 확정 예정 일시 — 접수(생성) 시각 + {@value #CONFIRM_LEAD_DAYS}일 고정. 미영속 상태면 null. */
    public LocalDateTime confirmExpectedAt() {
        LocalDateTime receivedAt = getCreatedAt();
        return (receivedAt == null) ? null : receivedAt.plusDays(CONFIRM_LEAD_DAYS);
    }

    /**
     * 확정 예정일까지 남은 일수(D-day) — 진행중(REQUESTED·CONTACTING) 예약만, 그 외 null.
     * 예정일이 지났는데 아직 진행중이면 0에서 멈추지 않고 음수로 계속 감소한다(도메인 확정 규칙).
     */
    public Long confirmDDay() {
        if (!reservationStatus.isInProgress()) {
            return null;
        }
        LocalDateTime expectedAt = confirmExpectedAt();
        if (expectedAt == null) {
            return null;
        }
        return ChronoUnit.DAYS.between(LocalDate.now(), expectedAt.toLocalDate());
    }

    /**
     * 예약을 취소한다 — 진행중·확정 상태에서만 가능. 방문 완료된 예약은 취소할 수 없고,
     * 이미 취소된 예약의 재취소는 거부한다(사용 포인트 복원은 서비스가 PointPort로 수행).
     */
    public void cancel() {
        if (reservationStatus == ReservationStatus.CANCELED) {
            throw new BusinessException(ReservationErrorCode.ALREADY_CANCELED);
        }
        if (reservationStatus == ReservationStatus.VISITED) {
            throw new BusinessException(ReservationErrorCode.CANNOT_CANCEL);
        }
        this.reservationStatus = ReservationStatus.CANCELED;
        this.paymentStatus = PaymentStatus.CANCELED;
    }

    /**
     * 어드민 상태 변경 — 실수 정정을 위해 어떤 상태로든 전이할 수 있다(자유 전이, 도메인 확정 규칙).
     * 결제 상태 동반 전이: CONFIRMED 진입 시 수수료 결제 완료(PAID), CANCELED 진입 시 결제 취소
     * ({@link #cancel()}과 동일). 그 외 상태로의 이동(되살림 포함)은 결제 상태를 바꾸지 않는다
     * (세밀한 정정은 추후 어드민 결제 상태 변경 기능에서 다룬다).
     */
    public void changeStatusByAdmin(ReservationStatus targetStatus) {
        if (targetStatus == ReservationStatus.CONFIRMED) {
            this.paymentStatus = PaymentStatus.PAID;
        }
        if (targetStatus == ReservationStatus.CANCELED) {
            this.paymentStatus = PaymentStatus.CANCELED;
        }
        this.reservationStatus = targetStatus;
    }

    /** 이 예약이 포인트를 사용했는지 — 취소 시 복원 필요 여부 판단용. */
    public boolean usedPointExists() {
        return usedPoint > 0;
    }

    /**
     * 취소 시 사용 포인트 환불 가능 여부 — 진행중(REQUESTED·CONTACTING) 취소만 환불하고,
     * 식당이 방문을 확정(CONFIRMED)한 뒤의 취소는 환불하지 않는다(도메인 확정 규칙).
     * 상태 전이 전에 판정해야 하므로 {@link #cancel()} 호출 전에 확인한다.
     */
    public boolean refundableOnCancel() {
        return reservationStatus.isInProgress();
    }
}
