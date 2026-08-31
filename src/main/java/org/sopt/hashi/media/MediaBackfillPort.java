package org.sopt.hashi.media;

import java.util.Collection;

/**
 * association 소유 도메인의 migration runner만 사용하는 임시 공개 경계.
 * 일반 Service와 Controller는 사용하지 않으며, legacy 전환 종료 조건 충족 후 runner와 함께 제거한다.
 */
public interface MediaBackfillPort {

    /**
     * runner가 transaction 밖에서 source identity를 재확인하고 소유 association을 잠근 뒤 호출한다.
     * READY 자산의 BOUND 전이와 association의 public asset ID 저장은 같은 쓰기 transaction에 참여한다.
     * 이미 연결된 자산은 재사용하지 않는다. 재실행 시 동일 연결 건너뛰기는 association 소유자가 담당한다.
     */
    void claimReady(Collection<MediaBackfillClaim> claims);
}
