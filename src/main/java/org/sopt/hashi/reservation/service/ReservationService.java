package org.sopt.hashi.reservation.service;

import java.util.List;
import org.sopt.hashi.auth.CurrentUserProvider;
import org.sopt.hashi.reservation.code.ReservationErrorCode;
import org.sopt.hashi.reservation.domain.Reservation;
import org.sopt.hashi.reservation.domain.ReservationRepository;
import org.sopt.hashi.reservation.domain.ReservationType;
import org.sopt.hashi.reservation.dto.CreateAnywhereReservationRequest;
import org.sopt.hashi.reservation.dto.CreateReservationRequest;
import org.sopt.hashi.reservation.dto.ReservationListResponse;
import org.sopt.hashi.reservation.dto.ReservationResponse;
import org.sopt.hashi.reservation.dto.ReservationStatusFilter;
import org.sopt.hashi.restaurant.RestaurantInfo;
import org.sopt.hashi.restaurant.RestaurantPort;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 예약 생성·조회. 예약자는 {@link CurrentUserProvider}에서 얻는다. 등록 식당(STANDARD)의 존재·이름은
 * {@link RestaurantPort}로, 미등록 식당(ANYWHERE)의 이름·주소는 예약에 저장된 값으로 처리한다(§5).
 */
@Service
public class ReservationService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    /** 등록 식당이 삭제돼 이름을 얻지 못할 때의 표시 대체값(§5-2 존재하는 것만 반환 → 호출 측 fallback). */
    private static final String UNKNOWN_RESTAURANT_NAME = "알 수 없는 식당";

    private final ReservationRepository reservationRepository;
    private final RestaurantPort restaurantPort;
    private final CurrentUserProvider currentUserProvider;

    public ReservationService(ReservationRepository reservationRepository,
                              RestaurantPort restaurantPort,
                              CurrentUserProvider currentUserProvider) {
        this.reservationRepository = reservationRepository;
        this.restaurantPort = restaurantPort;
        this.currentUserProvider = currentUserProvider;
    }

    /** 등록 식당 예약을 생성한다. 식당이 존재하지 않으면 생성을 거부한다. */
    @Transactional
    public ReservationResponse create(CreateReservationRequest request) {
        Long userId = currentUserProvider.currentUserId();
        if (!restaurantPort.existsById(request.restaurantId())) {
            throw new BusinessException(ReservationErrorCode.RESTAURANT_NOT_FOUND);
        }
        Reservation reservation = reservationRepository.save(Reservation.standard(
                userId, request.reserverName(), request.restaurantId(), request.reservedAt(),
                request.adultCount(), request.teenCount(), request.childCount(), request.requestNote()));
        return toResponse(reservation);
    }

    /** 미등록 식당(어디든) 예약을 생성한다. 식당 존재 검증 없이 입력받은 식당명·주소를 저장한다. */
    @Transactional
    public ReservationResponse createAnywhere(CreateAnywhereReservationRequest request) {
        Long userId = currentUserProvider.currentUserId();
        Reservation reservation = reservationRepository.save(Reservation.anywhere(
                userId, request.reserverName(), request.restaurantName(), request.restaurantAddress(),
                request.reservedAt(),
                request.adultCount(), request.teenCount(), request.childCount(), request.requestNote()));
        return toResponse(reservation);
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

        List<ReservationResponse> reservations = page.stream().map(this::toResponse).toList();
        return new ReservationListResponse(reservations, nextCursor, hasNext);
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

    /** 현재 사용자의 예약 단건을 조회한다. 본인 소유가 아니면 접근을 거부한다(auth.md §5). */
    @Transactional(readOnly = true)
    public ReservationResponse getMyReservation(Long reservationId) {
        Long userId = currentUserProvider.currentUserId();
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.NOT_FOUND));
        if (!reservation.ownedBy(userId)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        return toResponse(reservation);
    }

    // 쿼리스트링으로 받은 size값이 정상적인 사이즈 값인지 검증. 정상적이지 않으면 size = 20으로 반환
    private int normalizeSize(Integer size) {
        if (size == null || size < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    /**
     * 유형별로 식당명을 해석해 응답을 만든다. ANYWHERE는 저장된 식당명을, STANDARD는 RestaurantPort로 enrich한
     * 식당명을 쓴다(목록에서는 건별 호출 — 배치 최적화는 필요 시 별도).
     */
    private ReservationResponse toResponse(Reservation reservation) {
        String restaurantName;
        if (reservation.getReservationType() == ReservationType.ANYWHERE) {
            restaurantName = reservation.getRestaurantName();
        } else {
            restaurantName = restaurantPort.findSummaryById(reservation.getRestaurantId())
                    .map(RestaurantInfo::name)
                    .orElse(UNKNOWN_RESTAURANT_NAME);
        }
        return ReservationResponse.of(reservation, restaurantName);
    }
}
