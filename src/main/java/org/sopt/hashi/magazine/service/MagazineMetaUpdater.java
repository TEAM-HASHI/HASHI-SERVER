package org.sopt.hashi.magazine.service;

import lombok.extern.slf4j.Slf4j;
import org.sopt.hashi.magazine.domain.MagazineMetaRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 매거진 카운터 비동기 갱신. 좋아요 요청 트랜잭션은 리액션 행까지만 확정하고, 카운터 UPDATE는 커밋 뒤
 * 이 빈이 별도 스레드·별도 트랜잭션에서 실행한다 — 같은 매거진에 좋아요가 몰릴 때 카운터 행 락 대기가
 * 사용자 응답을 막지 않게 하기 위함이다(준실시간 카운터 전제).
 * {@code @Async}는 프록시를 거쳐야 하므로 호출 측과 다른 빈에 두며, 예외는 호출자에게 전달되지 않아 여기서 로그로 남긴다.
 */
@Slf4j
@Component
public class MagazineMetaUpdater {

    private final MagazineMetaRepository magazineMetaRepository;

    public MagazineMetaUpdater(MagazineMetaRepository magazineMetaRepository) {
        this.magazineMetaRepository = magazineMetaRepository;
    }

    @Async
    @Transactional
    public void increaseLikeCount(Long magazineId) {
        if (magazineMetaRepository.increaseLikeCount(magazineId) == 0) {
            log.warn("매거진 좋아요 수 증가 실패 — meta 행 없음. magazineId={}", magazineId);
        }
    }

    @Async
    @Transactional
    public void decreaseLikeCount(Long magazineId) {
        if (magazineMetaRepository.decreaseLikeCount(magazineId) == 0) {
            log.warn("매거진 좋아요 수 감소 실패 — meta 행 없음 또는 이미 0. magazineId={}", magazineId);
        }
    }
}
