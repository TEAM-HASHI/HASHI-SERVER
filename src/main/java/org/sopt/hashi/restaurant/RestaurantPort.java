package org.sopt.hashi.restaurant;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * restaurant 모듈의 공개 포트 — 타 도메인(reservation·review 등)이 식당을 참조할 때 쓰는 최소 계약.
 * 의존 모듈은 restaurant 내부(엔티티·Repository·서비스)가 아니라 이 포트에만 코딩하고, 테스트에선 모킹한다.
 */
public interface RestaurantPort {

    /** 식당 존재 여부를 확인한다 — 예약·리뷰 생성 시 식당 존재 검증용. soft-delete된 식당도 존재로 본다. */
    boolean existsById(Long restaurantId);

    /** 삭제되지 않은 식당인지 확인한다 — 컬렉션 저장처럼 사용자에게 노출 가능한 식당만 받아야 하는 검증용(#216). */
    boolean existsActiveById(Long restaurantId);

    /** 식당 요약 정보를 조회한다 — 식당명 등 표시·enrich용. 없으면 empty. */
    Optional<RestaurantInfo> findSummaryById(Long restaurantId);

    /** 여러 식당의 요약 정보를 한 번에 조회한다 — 목록 enrich용(§5-2). 존재하는 식당만 반환한다(삭제된 식당 포함). */
    List<RestaurantInfo> findSummaries(Collection<Long> restaurantIds);

    /** 식당 상세 정보를 조회한다 — 예약 상세 등 요약보다 많은 표시 정보가 필요한 조회용. 없으면 empty(삭제된 식당 포함). */
    Optional<RestaurantDetailInfo> findDetailById(Long restaurantId);

    /**
     * 사용자 노출용 식당 상세 목록 — 매거진 연결 식당 카드처럼 평점·메뉴 이미지·영업시간·가격대까지 내리는 enrich용.
     * 삭제된 식당은 제외하고, 요청한 ID 순서를 유지하며 존재하는 식당만 반환한다.
     */
    List<RestaurantDetailInfo> findActiveDetails(Collection<Long> restaurantIds);

    /**
     * 컬렉션 목록/핀용 공개 식당 정보. 삭제/없는 식당은 생략하고 위치 미준비/만료 식당은 location=null로 남긴다.
     * 최초 입력 순서를 유지하고 중복 ID는 제거한다. null/빈 입력은 빈 목록, null/0/음수 ID는 잘못된 입력이다.
     * 내부 500개 단위로 조회하며 전체 결과를 모은 뒤 반환한다. 중간 조회 실패는 부분 결과 없이 전파한다.
     * 반환된 좌표도 validUntil부터 사용할 수 없으며 호출자는 응답 직전 자신의 권한/컬렉션 버전을 재검사한다.
     */
    List<RestaurantMapInfo> findActiveMapInfos(Collection<Long> restaurantIds);

    /**
     * 사용자 노출용 식당 카드 목록 — 저장 컬렉션처럼 카드 표시와 평점·리뷰 수 정렬, 음식점 분류 필터가 필요한 enrich용(#216).
     * 상세와 달리 영업시간·이미지 전체·가격대는 읽지 않는다. 삭제된 식당은 제외하고 존재하는 식당만 반환하며 순서는 보장하지 않는다.
     */
    List<RestaurantCardInfo> findActiveCards(Collection<Long> restaurantIds);
    /** 리뷰 생성 시 식당 평점 합계·리뷰 수·평균을 원자적으로 증가시킨다. */
    void increaseReviewStatistics(Long restaurantId, int rating);

    /** 리뷰 삭제 시 식당 평점 합계·리뷰 수·평균을 원자적으로 감소시킨다. */
    void decreaseReviewStatistics(Long restaurantId, int rating);

    /** 리뷰 별점 변경 시 리뷰 수는 유지하고 평점 합계·평균을 원자적으로 갱신한다. */
    void updateReviewRatingStatistics(Long restaurantId, int oldRating, int newRating);

    /** 어드민 식당 등록 — 이미지·메뉴 사진은 업로드 완료된 S3 키로 받는다. */
    AdminRestaurantInfo createByAdmin(AdminRestaurantCommand command);

    /**
     * 어드민 식당 수정 — 부분 수정(PATCH). null 필드는 변경하지 않고, 컬렉션은 전체 교체한다.
     * 식당이 없으면 BusinessException(RESTAURANT-004 NOT_FOUND).
     */
    AdminRestaurantInfo updateByAdmin(Long restaurantId, AdminRestaurantCommand command);

    /**
     * 어드민 식당 삭제(soft delete) — deleted를 올려 사용자 노출만 차단하고, 예약·리뷰가 참조하는
     * 데이터는 보존한다. 식당이 없으면 BusinessException(RESTAURANT-004 NOT_FOUND).
     */
    void deleteByAdmin(Long restaurantId);

    /** 관리자 위치 상태 조회. Entity/내부 enum과 provider 원문을 반환하지 않는다. */
    RestaurantLocationInfo getLocationByAdmin(Long restaurantId);

    /** expectedAddressRevision이 현재와 일치할 때 같은 주소의 새 작업을 등록한다. */
    RestaurantLocationInfo retryLocationByAdmin(Long restaurantId, long expectedAddressRevision);

    /** 활성·비활성 지역을 표시 순서·ID 순으로 조회한다. page는 0부터, size는 1~100이다. */
    AdminMapRegionInfo.Page getMapRegionsByAdmin(int page, int size);

    /** 안정적인 code로 전체 설정을 교체한다. 동일 요청 재전송·동시 생성에도 ID는 유지한다. */
    AdminMapRegionInfo upsertMapRegionByAdmin(String code, AdminMapRegionCommand command);

    /** 없는/삭제 식당은 거절하고, 존재하는 비활성 지역도 지정 가능하다. null은 소속 해제다. */
    Long assignMapRegionByAdmin(Long restaurantId, Long mapRegionId);
}
