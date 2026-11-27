package org.sopt.hashi.user.service;

import org.sopt.hashi.user.domain.SignupPhoneStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "hashi.signup-phone.enabled", havingValue = "true")
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
