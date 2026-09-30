package org.sopt.hashi.magazine;

/**
 * magazine 모듈의 공개 포트 — 진입점(admin)이 매거진을 관리(등록·수정·삭제)할 때 쓰는 계약.
 * 의존 모듈은 magazine 내부(엔티티·Repository·서비스)가 아니라 이 포트에만 코딩한다.
 */
public interface MagazinePort {

    /**
     * 어드민 매거진 등록 — 배너·썸네일·카드뉴스 이미지는 legacy key 또는 public asset ID로 받는다.
     * 상세 화면 데이터(본문·카드뉴스·해시태그·연결 식당)는 선택이다.
     * 연결 식당에 없는 식당이나 삭제된 식당이 있으면 BusinessException(MAGAZINE-002 RESTAURANT_NOT_FOUND),
     * 같은 asset을 카드뉴스 두 장에 쓰면 BusinessException(MAGAZINE-003 CARD_NEWS_ASSET_DUPLICATED).
     */
    MagazineInfo createByAdmin(AdminMagazineCommand command);

    /**
     * 어드민 매거진 수정 — 부분 수정(PATCH). null 필드는 변경하지 않고, 카드뉴스·해시태그·연결 식당은
     * 보내면 전체 교체한다(빈 목록은 모두 지움). 매거진이 없으면 BusinessException(MAGAZINE-001 NOT_FOUND).
     * 연결 식당·카드뉴스 asset 검증은 등록과 같다(MAGAZINE-002, MAGAZINE-003).
     */
    MagazineInfo updateByAdmin(Long magazineId, AdminMagazineCommand command);

    /** 어드민 매거진 삭제 — 매거진이 없으면 BusinessException(MAGAZINE-001 NOT_FOUND). */
    void deleteByAdmin(Long magazineId);
}
