package org.sopt.hashi.user.internal.recovery;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.sopt.hashi.user.UserWithdrawnEvent;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 탈퇴 이벤트 재제출기. 구독 리스너가 실패하면 Event Publication Registry에 publication이 미완료로 남는데,
 * Spring Modulith는 기본 설정(republish-outstanding-events-on-restart=false)에서 재시작해도 이를 다시 보내지 않으므로
 * 기동 직후와 주기적으로 {@link UserWithdrawnEvent} publication만 골라 재제출한다(media 재제출기와 같은 패턴).
 * 탈퇴 이벤트는 양이 적어 한 번에 전부 보낸다 — 건수 상한을 두면 오래된 같은 묶음만 반복 선택돼 나머지가 영영 밀린다.
 * 구독 리스너는 멱등이라 재제출이 안전하다. 미완료가 남아 있다는 사실은 운영 관측 대상이라 WARN으로 남긴다.
 */
@Slf4j
@Component
public class UserEventPublicationRecovery {

    /** 주기 실행이 건드리지 않는 publication 나이 — 방금 발행돼 아직 처리 중인 것을 두 번 보내지 않기 위한 여유. */
    static final Duration RESUBMIT_AGE = Duration.ofMinutes(1);
    private static final long RESUBMIT_INTERVAL_MILLIS = 60_000L;

    private final IncompleteEventPublications incompletePublications;
    private final Clock clock;

    public UserEventPublicationRecovery(IncompleteEventPublications incompletePublications,
                                        @Qualifier("japanClock") Clock clock) {
        this.incompletePublications = incompletePublications;
        this.clock = clock;
    }

    /** 기동 직후 — 재배포·장애로 남은 publication을 나이와 무관하게 재제출한다. 실패해도 기동은 막지 않고 주기 실행이 다시 시도한다. */
    @EventListener(ApplicationReadyEvent.class)
    public void resubmitOnStartup() {
        try {
            resubmit(publication -> true, "startup");
        } catch (RuntimeException e) {
            log.warn("기동 시 탈퇴 이벤트 재제출 실패 — 주기 실행이 다시 시도한다. errorType={}", e.getClass().getSimpleName());
        }
    }

    /** 주기 실행 — 기준 시간보다 오래된 publication만 재제출한다. */
    @Scheduled(fixedDelay = RESUBMIT_INTERVAL_MILLIS, initialDelay = RESUBMIT_INTERVAL_MILLIS)
    public void resubmitOldPublications() {
        Instant publishedBefore = Instant.now(clock).minus(RESUBMIT_AGE);
        resubmit(publication -> !publication.getPublicationDate().isAfter(publishedBefore), "scheduled");
    }

    private void resubmit(Predicate<EventPublication> eligible, String trigger) {
        AtomicInteger resubmitted = new AtomicInteger();
        incompletePublications.resubmitIncompletePublications(publication -> {
            boolean selected = publication.getEvent() instanceof UserWithdrawnEvent && eligible.test(publication);
            if (selected) {
                resubmitted.incrementAndGet();
            }
            return selected;
        });
        if (resubmitted.get() > 0) {
            log.warn("미완료 회원 탈퇴 이벤트 재제출. trigger={}, count={}", trigger, resubmitted.get());
        }
    }
}
