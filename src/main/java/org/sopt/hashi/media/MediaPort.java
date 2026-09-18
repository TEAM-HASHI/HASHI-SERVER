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

    /** 요청한 asset-role projection을 고정된 bulk 조회로 반환한다. 조회 불일치는 결과에서 제외한다. */
    Map<MediaImageRequest, MediaImage> findImages(Collection<MediaImageRequest> requests);
}
