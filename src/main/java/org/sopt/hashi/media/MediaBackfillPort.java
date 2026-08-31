package org.sopt.hashi.media;

import java.util.Collection;

/**
 * association 소유 도메인의 migration runner만 사용하는 임시 공개 경계.
 * 일반 Service와 Controller는 사용하지 않으며, legacy 전환 종료 조건 충족 후 runner와 함께 제거한다.
 */
public interface MediaBackfillPort {

    /** transaction 밖에서 source를 HEAD하고 기존 예약만 조회한다. asset, copy와 job은 생성하지 않는다. */
    MediaBackfillInspectionInfo inspect(MediaBackfillReference reference);

    /**
     * inspect에서 확인한 identity가 여전히 일치할 때만 예약·복사·변환 요청을 준비한다.
     * DB transaction 밖에서 호출하며, READY 여부 확인과 domain 연결은 별도 단계다.
     */
    MediaBackfillAssetInfo prepare(MediaBackfillReference reference, String expectedIdentityHash);

    /**
     * runner가 transaction 밖에서 source identity를 재확인하고 소유 association을 잠근 뒤 호출한다.
     * READY 자산의 BOUND 전이와 association의 public asset ID 저장은 같은 쓰기 transaction에 참여한다.
     * 이미 연결된 자산은 재사용하지 않는다. 재실행 시 동일 연결 건너뛰기는 association 소유자가 담당한다.
     */
    void claimReady(Collection<MediaBackfillClaim> claims);
}
