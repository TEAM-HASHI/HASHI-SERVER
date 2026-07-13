package org.sopt.hashi.magazine;

/**
 * magazine 모듈의 공개 포트 — 진입점(admin)이 매거진을 관리(등록·수정·삭제)할 때 쓰는 계약.
 * 의존 모듈은 magazine 내부(엔티티·Repository·서비스)가 아니라 이 포트에만 코딩한다.
 */
public interface MagazinePort {

    /** 어드민 매거진 등록 — 배너·썸네일 이미지는 업로드 완료된 S3 키(bannerKey·thumbnailKey)로 받는다. */
    MagazineInfo createByAdmin(String title, String bannerKey, String thumbnailKey, String instagramRedirectUrl);

    /**
     * 어드민 매거진 수정 — 부분 수정(PATCH). null 필드는 변경하지 않는다.
     * 매거진이 없으면 BusinessException(MAGAZINE-001 NOT_FOUND).
     */
    MagazineInfo updateByAdmin(Long magazineId, String title, String bannerKey, String thumbnailKey,
                               String instagramRedirectUrl);

    /** 어드민 매거진 삭제 — 매거진이 없으면 BusinessException(MAGAZINE-001 NOT_FOUND). */
    void deleteByAdmin(Long magazineId);
}
