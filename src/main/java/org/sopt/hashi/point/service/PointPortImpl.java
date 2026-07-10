package org.sopt.hashi.point.service;

import java.util.Collection;
import java.util.Map;
import org.sopt.hashi.point.PointPort;
import org.sopt.hashi.point.PointSourceType;
import org.springframework.stereotype.Component;

/**
 * point 공개 포트 실구현 — {@link PointService}에 위임한다. 트랜잭션 경계는 서비스가 갖는다
 * (REQUIRED — 호출자 트랜잭션이 있으면 참여, §8).
 */
@Component
class PointPortImpl implements PointPort {

    private final PointService pointService;

    PointPortImpl(PointService pointService) {
        this.pointService = pointService;
    }

    @Override
    public void earn(Long userId, long amount, String reason, PointSourceType sourceType, Long sourceId) {
        pointService.earn(userId, amount, reason, sourceType, sourceId);
    }

    @Override
    public long earnReviewReward(Long userId, Long reservationId) {
        return pointService.earnReviewReward(userId, reservationId);
    }

    @Override
    public long findEarnedAmount(PointSourceType sourceType, Long sourceId) {
        return pointService.findEarnedAmount(sourceType, sourceId);
    }

    @Override
    public Map<Long, Long> findEarnedAmounts(PointSourceType sourceType, Collection<Long> sourceIds) {
        return pointService.findEarnedAmounts(sourceType, sourceIds);
    }

    @Override
    public void use(Long userId, long amount, String reason, PointSourceType sourceType, Long sourceId) {
        pointService.use(userId, amount, reason, sourceType, sourceId);
    }

    @Override
    public void restore(Long userId, PointSourceType sourceType, Long sourceId) {
        pointService.restore(userId, sourceType, sourceId);
    }

    @Override
    public boolean isRestored(PointSourceType sourceType, Long sourceId) {
        return pointService.isRestored(sourceType, sourceId);
    }

    @Override
    public long getBalance(Long userId) {
        return pointService.getBalance(userId);
    }
}
