package org.sopt.hashi.reservation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.sopt.hashi.BaseTimeEntity;
import org.sopt.hashi.reservation.ReservationStatus;

/**
 * 예약 애그리거트 루트. 예약자(userId)·대상 식당(restaurantId)은 타 모듈 소유이므로 FK·연관관계 없이
 * Long ID 값으로만 보관한다(§5). 결제·포인트(payment_status·amount·used_point)는 #40 범위 밖이라 매핑하지 않는다.
 *
 * <p>유형(STANDARD/ANYWHERE)에 따라 식당 참조 방식이 다르다. STANDARD는 {@code restaurantId}로 참조하고
 * 식당명은 RestaurantPort로 조회한다. ANYWHERE는 미등록 식당이라 {@code restaurantName}·{@code restaurantAddress}를
 * 직접 보관하고 {@code restaurantId}는 null이다.
 */
@Getter
@Entity
@Table(name = "reservation")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reservation extends BaseTimeEntity {

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

    @Column(name = "request_note", length = 500)
    private String requestNote;

    private Reservation(ReservationType reservationType, Long userId, String reserverName,
                        Long restaurantId, String restaurantName, String restaurantAddress,
                        LocalDateTime reservedAt, int adultCount, int teenCount, int childCount,
                        String requestNote) {
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
    }

    /** 등록 식당 예약을 생성한다(초기 상태 REQUESTED). 식당 존재 검증은 호출 측 서비스가 포트로 수행한다. */
    public static Reservation standard(Long userId, String reserverName, Long restaurantId,
                                       LocalDateTime reservedAt,
                                       int adultCount, int teenCount, int childCount, String requestNote) {
        return new Reservation(ReservationType.STANDARD, userId, reserverName,
                restaurantId, null, null,
                reservedAt, adultCount, teenCount, childCount, requestNote);
    }

    /** 미등록 식당(어디든) 예약을 생성한다. 식당명·주소를 직접 받아 보관한다. */
    public static Reservation anywhere(Long userId, String reserverName,
                                       String restaurantName, String restaurantAddress,
                                       LocalDateTime reservedAt,
                                       int adultCount, int teenCount, int childCount, String requestNote) {
        return new Reservation(ReservationType.ANYWHERE, userId, reserverName,
                null, restaurantName, restaurantAddress,
                reservedAt, adultCount, teenCount, childCount, requestNote);
    }

    /** 주어진 사용자가 이 예약의 소유자인지 확인한다(본인 리소스 접근 검증용). */
    public boolean ownedBy(Long userId) {
        return this.userId.equals(userId);
    }
}
