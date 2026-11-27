package org.sopt.hashi.point.service;

import org.sopt.hashi.point.PointSourceType;
import org.sopt.hashi.user.FriendInviteReward;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class PointFriendInviteReward implements FriendInviteReward {
    private static final long REWARD = 4_000L;
    private final PointService points;

    PointFriendInviteReward(PointService points) {
        this.points = points;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void grant(Long inviterId, Long inviteeId) {
        if (inviterId.equals(inviteeId)) {
            throw new IllegalArgumentException("본인에게 초대 보상을 지급할 수 없습니다");
        }
        points.earn(inviterId, REWARD, "친구 초대 보상", PointSourceType.INVITE_SENT, inviteeId);
        points.earn(inviteeId, REWARD, "초대 코드 가입 보상", PointSourceType.INVITE_RECEIVED, inviteeId);
    }
}
