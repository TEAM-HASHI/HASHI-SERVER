package org.sopt.hashi.reservation.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.sopt.hashi.auth.CurrentUserProvider;
import org.sopt.hashi.point.PointPort;
import org.sopt.hashi.point.PointSourceType;
import org.sopt.hashi.reservation.AdminReservationInfo;
import org.sopt.hashi.reservation.ReservationStatus;
import org.sopt.hashi.reservation.ReservationType;
import org.sopt.hashi.reservation.code.ReservationErrorCode;
import org.sopt.hashi.reservation.domain.Reservation;
import org.sopt.hashi.reservation.domain.ReservationRepository;
import org.sopt.hashi.reservation.dto.CreateAnywhereReservationRequest;
import org.sopt.hashi.reservation.dto.CreateReservationRequest;
import org.sopt.hashi.reservation.dto.ReservationDetailResponse;
import org.sopt.hashi.reservation.dto.ReservationListResponse;
import org.sopt.hashi.reservation.dto.ReservationResponse;
import org.sopt.hashi.reservation.dto.ReservationStatusFilter;
import org.sopt.hashi.restaurant.RestaurantDetailInfo;
import org.sopt.hashi.restaurant.RestaurantInfo;
import org.sopt.hashi.restaurant.RestaurantPort;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.user.UserInfo;
import org.sopt.hashi.user.UserPort;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 예약 생성·조회. 예약자는 {@link CurrentUserProvider}에서 얻는다. 등록 식당(STANDARD)의 존재·이름은
 * {@link RestaurantPort}로, 미등록 식당(ANYWHERE)의 이름·주소는 예약에 저장된 값으로 처리한다(§5).
 *
 * <p>어드민 유스케이스(상태 변경·전체 목록·예약자 조회)도 이 서비스가 담당한다 — 포인트 복원은
 * admin이 접근할 수 없는 {@link PointPort} 소관이고(부록 의존 방향), 예약자 조회는 §5-3 교차 조회
 * 담당 규칙(예약의 예약자 조회 = reservation 담당)에 따른다. admin 모듈은 ReservationPort로 위임만 한다.
 */
@Slf4j
@Service
public class ReservationService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    /** 등록 식당이 삭제돼 이름을 얻지 못할 때의 표시 대체값(§5-2 존재하는 것만 반환 → 호출 측 fallback). */
    private static final String UNKNOWN_RESTAURANT_NAME = "알 수 없는 식당";

    /** 포인트 차감 원장의 사유 표기. */
    private static final String POINT_USE_REASON = "예약 결제 수수료";

    private final ReservationRepository reservationRepository;
    private final RestaurantPort restaurantPort;
    private final PointPort pointPort;
    private final UserPort userPort;
    private final CurrentUserProvider currentUserProvider;

    public ReservationService(ReservationRepository reservationRepository,
                              RestaurantPort restaurantPort,
                              PointPort pointPort,
                              UserPort userPort,
                              CurrentUserProvider currentUserProvider) {
        this.reservationRepository = reservationRepository;
        this.restaurantPort = restaurantPort;
        this.pointPort = pointPort;
        this.userPort = userPort;
        this.currentUserProvider = currentUserProvider;
    }

    /** 등록 식당 예약을 생성한다. 식당이 존재하지 않으면 거부하고, 사용 포인트는 같은 트랜잭션에서 차감한다(§8). */
    @Transactional
    public ReservationResponse create(CreateReservationRequest request) {
        Long userId = currentUserProvider.currentUserId();
        if (!restaurantPort.existsById(request.restaurantId())) {
            throw new BusinessException(ReservationErrorCode.RESTAURANT_NOT_FOUND);
        }
        long usedPoint = defaultUsedPoint(request.usedPoint());
        Reservation reservation = reservationRepository.save(Reservation.standard(
                userId, request.reserverName(), request.restaurantId(), request.reservedAt(),
                request.adultCount(), request.teenCount(), request.childCount(), request.requestNote(),
                usedPoint, request.amount()));
        usePointIfAny(userId, reservation);
        return toResponse(reservation);
    }

    /** 미등록 식당(어디든) 예약을 생성한다. 식당 존재 검증 없이 저장하며, 사용 포인트는 같은 트랜잭션에서 차감한다. */
    @Transactional
    public ReservationResponse createAnywhere(CreateAnywhereReservationRequest request) {
        Long userId = currentUserProvider.currentUserId();
        long usedPoint = defaultUsedPoint(request.usedPoint());
        Reservation reservation = reservationRepository.save(Reservation.anywhere(
                userId, request.reserverName(), request.restaurantName(), request.restaurantAddress(),
                request.reservedAt(),
                request.adultCount(), request.teenCount(), request.childCount(), request.requestNote(),
                usedPoint, request.amount()));
        usePointIfAny(userId, reservation);
        return toResponse(reservation);
    }

    /**
     * 예약을 취소한다 — 본인 소유만(미존재·타인 소유 모두 404, auth.md §5). 상태 전이(도메인 규칙)와
     * 사용 포인트 복원을 한 트랜잭션에서 수행한다. 단, 확정(CONFIRMED) 후 취소는 포인트를 환불하지 않는다.
     * 중복 취소는 상태 규칙이 선차단하고 POINT-004가 백스톱.
     */
    @Transactional
    public ReservationResponse cancel(Long reservationId) {
        Long userId = currentUserProvider.currentUserId();
        Reservation reservation = reservationRepository.findById(reservationId)
                .filter(found -> found.ownedBy(userId))
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.NOT_FOUND));
        boolean refundable = reservation.refundableOnCancel();   // 전이 전에 판정
        reservation.cancel();
        restoreUsedPointIfRefundable(reservation, refundable);
        return toResponse(reservation);
    }

    /**
     * 취소로 인한 사용 포인트 복원 — 진행중 취소만 환불한다. 취소(복원)→어드민 되살림→재취소 흐름에서는
     * 이미 복원된 원장을 재복원하지 않는다(되살림 시 포인트 무처리 — 도메인 확정 규칙).
     */
    private void restoreUsedPointIfRefundable(Reservation reservation, boolean refundable) {
        boolean restorable = refundable && reservation.usedPointExists()
                && !pointPort.isRestored(PointSourceType.RESERVATION, reservation.getId());
        if (restorable) {
            pointPort.restore(reservation.getUserId(), PointSourceType.RESERVATION, reservation.getId());
            log.info("예약 취소 포인트 복원. reservationId={}, userId={}, amount={}",
                    reservation.getId(), reservation.getUserId(), reservation.getUsedPoint());
        }
    }

    /** usedPoint는 선택 필드 — 미전송(null)이면 0(포인트 미사용). */
    private long defaultUsedPoint(Long usedPoint) {
        return (usedPoint == null) ? 0L : usedPoint;
    }

    /** 예약이 포인트를 사용했으면 예약 저장과 같은 트랜잭션에서 차감한다(실패 시 예약 저장도 함께 롤백). */
    private void usePointIfAny(Long userId, Reservation reservation) {
        if (reservation.usedPointExists()) {
            pointPort.use(userId, reservation.getUsedPoint(), POINT_USE_REASON,
                    PointSourceType.RESERVATION, reservation.getId());
        }
    }

    /**
     * 현재 사용자의 예약 목록을 커서 페이지네이션으로 조회한다(최신순).
     * filter가 null이면 전체, 아니면 해당 탭의 상태 집합으로 거른다.
     */
    @Transactional(readOnly = true)
    public ReservationListResponse getMyReservations(Long cursor, Integer size, ReservationStatusFilter filter) {
        Long userId = currentUserProvider.currentUserId();
        int pageSize = normalizeSize(size);
        Pageable pageable = PageRequest.of(0, pageSize + 1);   // hasNext 판별을 위해 1건 더 조회

        List<Reservation> rows = fetchPage(userId, filter, cursor, pageable);

        boolean hasNext = rows.size() > pageSize;
        List<Reservation> page = hasNext ? rows.subList(0, pageSize) : rows;
        Long nextCursor = hasNext ? page.getLast().getId() : null;

        return new ReservationListResponse(toResponses(page), nextCursor, hasNext);
    }

    /** 페이지 단위 응답 변환 — STANDARD 식당 요약(이름·대표이미지)은 포트 다건 조회(findSummaries)로 한 번에 enrich한다(N+1 방지, §5-2). */
    private List<ReservationResponse> toResponses(List<Reservation> reservations) {
        Map<Long, RestaurantInfo> summaries = fetchRestaurantSummaries(reservations);
        return reservations.stream()
                .map(reservation -> toResponse(reservation, findSummary(summaries, reservation)))
                .toList();
    }

    /** ANYWHERE는 restaurantId가 null이라 맵을 조회하지 않는다(불변 빈 맵은 null 키 조회 시 NPE). */
    private RestaurantInfo findSummary(Map<Long, RestaurantInfo> summaries, Reservation reservation) {
        Long restaurantId = reservation.getRestaurantId();
        return (restaurantId == null) ? null : summaries.get(restaurantId);
    }

    private Map<Long, RestaurantInfo> fetchRestaurantSummaries(List<Reservation> reservations) {
        Set<Long> restaurantIds = reservations.stream()
                .filter(r -> r.getReservationType() == ReservationType.STANDARD && r.getRestaurantId() != null)
                .map(Reservation::getRestaurantId)
                .collect(Collectors.toSet());
        if (restaurantIds.isEmpty()) {
            return Map.of();
        }
        return restaurantPort.findSummaries(restaurantIds).stream()
                .collect(Collectors.toMap(RestaurantInfo::id, summary -> summary, (a, b) -> a));
    }

    // 한번에 받아온 일반 예약과 어디든 예약 — 유형별로 식당명·대표이미지·주소를 해석해 응답으로 변환
    // (STANDARD 주소는 저장하지 않고 포트로 live enrich — 식당 주소 변경 시 항상 최신)
    private ReservationResponse toResponse(Reservation reservation, RestaurantInfo summary) {
        if (reservation.getReservationType() == ReservationType.ANYWHERE) {
            return ReservationResponse.of(reservation,
                    reservation.getRestaurantName(), null, reservation.getRestaurantAddress());
        }
        if (summary == null) {
            return ReservationResponse.of(reservation, UNKNOWN_RESTAURANT_NAME, null, null);
        }
        return ReservationResponse.of(reservation, summary.name(), summary.imageUrl(), summary.address());
    }

    private List<Reservation> fetchPage(Long userId, ReservationStatusFilter filter, Long cursor, Pageable pageable) {
        if (filter == null) {
            return (cursor == null)
                    ? reservationRepository.findByUserIdOrderByIdDesc(userId, pageable)
                    : reservationRepository.findByUserIdAndIdLessThanOrderByIdDesc(userId, cursor, pageable);
        }
        return (cursor == null)
                ? reservationRepository.findByUserIdAndReservationStatusInOrderByIdDesc(
                        userId, filter.statuses(), pageable)
                : reservationRepository.findByUserIdAndReservationStatusInAndIdLessThanOrderByIdDesc(
                        userId, filter.statuses(), cursor, pageable);
    }

    /**
     * 현재 사용자의 예약 단건 상세를 조회한다. 본인 소유가 아니면 예약 존재 자체를 숨기기 위해
     * FORBIDDEN이 아닌 NOT_FOUND로 응답한다(열거 방지 — auth.md §5).
     */
    @Transactional(readOnly = true)
    public ReservationDetailResponse getMyReservation(Long reservationId) {
        Long userId = currentUserProvider.currentUserId();
        Reservation reservation = reservationRepository.findById(reservationId)
                .filter(found -> found.ownedBy(userId))
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.NOT_FOUND));
        return toDetailResponse(reservation);
    }

    /**
     * 유형별로 식당 표시 정보를 해석해 상세 응답을 만든다. ANYWHERE는 저장된 식당명·주소를 쓰고 일본어명·이미지는 없다.
     * STANDARD는 RestaurantPort.findDetailById로 enrich하며, 식당이 없으면 이름만 fallback한다.
     */
    private ReservationDetailResponse toDetailResponse(Reservation reservation) {
        if (reservation.getReservationType() == ReservationType.ANYWHERE) {
            return ReservationDetailResponse.of(reservation,
                    reservation.getRestaurantName(), null, reservation.getRestaurantAddress(), null);
        }
        Optional<RestaurantDetailInfo> detail = restaurantPort.findDetailById(reservation.getRestaurantId());
        return ReservationDetailResponse.of(reservation,
                detail.map(RestaurantDetailInfo::name).orElse(UNKNOWN_RESTAURANT_NAME),
                detail.map(RestaurantDetailInfo::nameJa).orElse(null),
                detail.map(RestaurantDetailInfo::address).orElse(null),
                detail.map(RestaurantDetailInfo::imageUrl).orElse(null));
    }

    // 쿼리스트링으로 받은 size값이 정상적인 사이즈 값인지 검증. 정상적이지 않으면 size = 20으로 반환
    private int normalizeSize(Integer size) {
        if (size == null || size < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    /**
     * 단건 응답 변환(생성 직후 응답용). STANDARD는 RestaurantPort 단건 조회(findSummaryById)로 요약을 얻어
     * {@link #toResponse(Reservation, RestaurantInfo)}에 위임한다. 목록은 {@link #toResponses(List)}가 다건 조회로 처리한다.
     */
    private ReservationResponse toResponse(Reservation reservation) {
        return toResponse(reservation, findSummaryFor(reservation));
    }

    /** 단건 식당 요약 조회 — STANDARD만 RestaurantPort로 얻고, ANYWHERE·미존재 식당은 null(호출 측 fallback). */
    private RestaurantInfo findSummaryFor(Reservation reservation) {
        return (reservation.getReservationType() == ReservationType.STANDARD)
                ? restaurantPort.findSummaryById(reservation.getRestaurantId()).orElse(null)
                : null;
    }

    /**
     * [어드민] 예약 상태 변경 — 자유 전이(되돌림 포함, 도메인 확정 규칙). CANCELED 진입 시 유저 취소와
     * 동일한 환불 규칙(진행중 취소만 복원)을 상태 전이와 같은 트랜잭션에서 적용한다(§8).
     */
    @Transactional
    public AdminReservationInfo changeStatusByAdmin(Long reservationId, ReservationStatus targetStatus) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.NOT_FOUND));
        ReservationStatus fromStatus = reservation.getReservationStatus();
        // 취소로 상태 변경 시 포인트 환불 가능 여부 판별(진행 중인 예약 한정)
        boolean refundable = targetStatus == ReservationStatus.CANCELED
                && reservation.refundableOnCancel();   // 전이 전에 판정
        reservation.changeStatusByAdmin(targetStatus);
        restoreUsedPointIfRefundable(reservation, refundable);
        // 자유 전이(되돌림 허용) 정책이라 정정 추적이 필요 — 이력 테이블 도입 전까지의 감사 기록.
        // 어드민 토큰의 subject가 adminId이므로 currentUserId()가 어드민 식별자를 돌려준다.
        log.info("어드민 예약 상태 변경. adminId={}, reservationId={}, {} -> {}",
                currentUserProvider.currentUserId(), reservationId, fromStatus, targetStatus);
        return toAdminInfo(reservation, findSummaryFor(reservation));
    }

    /** [어드민] 예약 목록 — 전체 사용자 대상 offset 페이지네이션(최신순). statusFilter가 null이면 전체(coding-style §4-2). */
    @Transactional(readOnly = true)
    public Page<AdminReservationInfo> findPageByAdmin(ReservationStatus statusFilter, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), normalizeSize(size),
                Sort.by(Sort.Direction.DESC, "id"));
        Page<Reservation> reservations = (statusFilter == null)
                ? reservationRepository.findAll(pageable)
                : reservationRepository.findByReservationStatus(statusFilter, pageable);
        Map<Long, RestaurantInfo> summaries = fetchRestaurantSummaries(reservations.getContent());
        return reservations.map(reservation -> toAdminInfo(reservation, findSummary(summaries, reservation)));
    }

    /**
     * [어드민] 예약자 정보 조회 — 교차 조회 담당 규칙(§5-3: 예약의 예약자 조회는 reservation 담당)에 따라
     * 여기서 {@link UserPort}로 enrich한다. 예약자가 없으면(탈퇴 등) RESERVER_NOT_FOUND.
     */
    @Transactional(readOnly = true)
    public UserInfo findReserverByAdmin(Long reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.NOT_FOUND));
        return userPort.findById(reservation.getUserId())
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.RESERVER_NOT_FOUND));
    }

    /** 어드민 전달용 요약 변환 — 유형별 식당 표시 정보 해석은 사용자향 응답과 같은 규칙을 따른다. */
    private AdminReservationInfo toAdminInfo(Reservation reservation, RestaurantInfo summary) {
        if (reservation.getReservationType() == ReservationType.ANYWHERE) {
            return toAdminInfo(reservation,
                    reservation.getRestaurantName(), null, reservation.getRestaurantAddress());
        }
        if (summary == null) {
            return toAdminInfo(reservation, UNKNOWN_RESTAURANT_NAME, null, null);
        }
        return toAdminInfo(reservation, summary.name(), summary.imageUrl(), summary.address());
    }

    private AdminReservationInfo toAdminInfo(Reservation reservation, String restaurantName,
                                             String restaurantImageUrl, String restaurantAddress) {
        return new AdminReservationInfo(
                reservation.getId(),
                reservation.getUserId(),
                reservation.getReservationType(),
                reservation.getReserverName(),
                reservation.getRestaurantId(),
                restaurantName,
                restaurantImageUrl,
                restaurantAddress,
                reservation.getReservedAt(),
                reservation.getAdultCount(),
                reservation.getTeenCount(),
                reservation.getChildCount(),
                reservation.getRequestNote(),
                reservation.getReservationStatus(),
                reservation.getPaymentStatus(),
                reservation.getUsedPoint(),
                reservation.getAmount(),
                reservation.confirmDDay());
    }
}
