package org.sopt.hashi.media;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** 콘텐츠 모듈의 일반 요청 경로가 사용하는 media 공개 facade. */
public interface MediaPort {

    /**
     * 추가 association의 asset을 claim하고 제거 association의 asset을 retire한다.
     * 모든 대상은 media 내부 PK 오름차순으로 잠그며 호출 transaction에 함께 참여한다.
     */
    void reconcileBindings(Collection<MediaAssetUse> claims, Collection<MediaAssetUse> retires);

    /**
     * 현재 ONBOARDING actor가 발급한 PROFILE asset을 새 회원에게 인계하고 bind한다.
     * 회원 생성·auth 계정 연결과 같은 transaction에서만 호출한다.
     */
    void claimOnboardingProfile(UUID assetId, Long newUserId);

    /** 요청한 asset-role projection을 고정된 bulk 조회로 반환한다. 조회 불일치는 결과에서 제외한다. */
    Map<MediaImageRequest, MediaImage> findImages(Collection<MediaImageRequest> requests);
}
