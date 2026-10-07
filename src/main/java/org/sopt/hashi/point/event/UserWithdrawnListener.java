package org.sopt.hashi.point.event;

import org.sopt.hashi.point.service.PointService;
import org.sopt.hashi.user.UserWithdrawnEvent;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * 회원 탈퇴 이벤트 구독 — 탈퇴 트랜잭션이 커밋된 뒤 별도 트랜잭션(REQUIRES_NEW)·별도 스레드에서 포인트 잔액을 소멸한다
 * (architecture.md §6·§8). 소멸은 멱등이라 Event Publication Registry가 재제출해도 한 번만 반영된다.
 */
@Component
public class UserWithdrawnListener {

    private final PointService pointService;

    public UserWithdrawnListener(PointService pointService) {
        this.pointService = pointService;
    }

    @ApplicationModuleListener
    public void on(UserWithdrawnEvent event) {
        pointService.forfeit(event.userId());
    }
}
