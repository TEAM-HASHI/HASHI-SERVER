package org.sopt.hashi.user;

/** 가입 트랜잭션 안에서 point가 두 회원의 친구 초대 보상을 적립한다. */
public interface FriendInviteReward {
    void grant(Long inviterId, Long inviteeId);
}
