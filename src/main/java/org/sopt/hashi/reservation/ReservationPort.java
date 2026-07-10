package org.sopt.hashi.reservation;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.sopt.hashi.user.UserInfo;
import org.springframework.data.domain.Page;

/**
 * reservation 모듈의 공개 포트 — 타 도메인(review 등)이 예약을 참조하거나 진입점(admin)이
 * 예약을 관리할 때 쓰는 계약. 의존 모듈은 reservation 내부(엔티티·Repository·서비스)가 아니라
 * 이 포트에만 코딩한다.
 */
public interface ReservationPort {

    /** 예약 요약을 조회한다 — 없으면 empty. */
    Optional<ReservationInfo> findById(Long reservationId);

    /** 리뷰 작성·조회에 필요한 예약 정보를 조회한다 — 없으면 empty. */
    Optional<ReservationReviewInfo> findReviewInfoById(Long reservationId);

    /** 여러 예약의 리뷰 화면용 정보를 한 번에 조회한다 — 목록 enrich용. */
    List<ReservationReviewInfo> findReviewInfos(Collection<Long> reservationIds);

    /** 사용자의 방문 완료 예약을 최신 방문순으로 조회한다. */
    List<ReservationReviewInfo> findVisitedReviewInfos(Long userId);

    /**
     * 어드민 예약 상태 변경 — 자유 전이(되돌림 포함). CANCELED 진입 시 진행중이던 예약의
     * 사용 포인트를 복원하며(유저 취소와 동일 규칙, 같은 트랜잭션 §8), 이미 복원된 출처는 재복원하지 않는다.
     * 예약이 없으면 BusinessException(RESERVATION-001 NOT_FOUND).
     */
    AdminReservationInfo changeStatusByAdmin(Long reservationId, ReservationStatus targetStatus);

    /**
     * 어드민 예약 목록 — 전체 사용자 대상 offset 페이지네이션(최신순), statusFilter가 null이면 전체.
     * (coding-style §4-2: 어드민 목록 = offset + 총건수)
     */
    Page<AdminReservationInfo> findPageByAdmin(ReservationStatus statusFilter, int page, int size);

    /**
     * 예약자(예약을 만든 회원) 정보 조회 — 교차 조회 담당 규칙(§5-3: 예약의 예약자 조회는 reservation 담당,
     * UserPort로 enrich). 예약이 없으면 RESERVATION-001, 예약자가 없으면(탈퇴 등) RESERVATION-007.
     */
    UserInfo findReserverByAdmin(Long reservationId);
}
