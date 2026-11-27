package org.sopt.hashi.user.service;

import org.sopt.hashi.user.domain.SignupPhoneStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

@Component
@ConditionalOnProperty(name = "spring.flyway.enabled", havingValue = "true", matchIfMissing = true)
class SignupPhoneCleanup {
    private final SignupPhoneStore store;

    SignupPhoneCleanup(SignupPhoneStore store) {
        this.store = store;
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    void purgeExpiredChallenges() {
        store.purgeExpired();
    }
}
