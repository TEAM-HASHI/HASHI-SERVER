package org.sopt.hashi.restaurant;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * restaurant 모듈의 공개 포트 — 타 도메인(reservation·review 등)이 식당을 참조할 때 쓰는 최소 계약.
 * 의존 모듈은 restaurant 내부(엔티티·Repository·서비스)가 아니라 이 포트에만 코딩하고, 테스트에선 모킹한다.
 */
public interface RestaurantPort {

    /** 식당 존재 여부를 확인한다 — 예약·리뷰 생성 시 식당 존재 검증용. */
    boolean existsById(Long restaurantId);

    /** 식당 요약 정보를 조회한다 — 식당명 등 표시·enrich용. 없으면 empty. */
    Optional<RestaurantInfo> findSummaryById(Long restaurantId);

    /** 여러 식당의 요약 정보를 한 번에 조회한다 — 목록 enrich용(§5-2). 존재하는 식당만 반환한다. */
    List<RestaurantInfo> findSummaries(Collection<Long> restaurantIds);

    /** 식당 상세 정보를 조회한다 — 예약 상세 등 이름·일본어명·주소·대표이미지가 필요한 조회용. 없으면 empty. */
    Optional<RestaurantDetailInfo> findDetailById(Long restaurantId);

    /** 어드민 식당 등록 — 이미지·메뉴 사진은 업로드 완료된 S3 키로 받는다. */
    AdminRestaurantInfo createByAdmin(AdminRestaurantCommand command);

    /**
     * 어드민 식당 수정 — 부분 수정(PATCH). null 필드는 변경하지 않고, 컬렉션은 전체 교체한다.
     * 식당이 없으면 BusinessException(RESTAURANT-004 NOT_FOUND).
     */
    AdminRestaurantInfo updateByAdmin(Long restaurantId, AdminRestaurantCommand command);

    /**
     * 어드민 식당 삭제(soft delete) — active를 내려 사용자 노출만 차단하고, 예약·리뷰가 참조하는
     * 데이터는 보존한다. 식당이 없으면 BusinessException(RESTAURANT-004 NOT_FOUND).
     */
    void deleteByAdmin(Long restaurantId);
}
