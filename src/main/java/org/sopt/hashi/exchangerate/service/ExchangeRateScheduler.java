package org.sopt.hashi.exchangerate.service;

import java.time.Clock;
import java.time.LocalDate;
import org.sopt.hashi.exchangerate.domain.ExchangeRateStore;
import org.sopt.hashi.exchangerate.internal.EximbankClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "hashi.exchange-rate.enabled", havingValue = "true")
class ExchangeRateScheduler {
    private final ExchangeRateStore store;
    private final EximbankClient bank;
    private final Clock clock;

    ExchangeRateScheduler(ExchangeRateStore store, EximbankClient bank, @Qualifier("japanClock") Clock clock) {
        this.store = store;
        this.bank = bank;
        this.clock = clock.withZone(ExchangeRateService.SEOUL);
    }

    // 정오 이후 재시작도 복구한다. 실제 호출 수와 다음 시도 시각은 서버들이 DB에서 공유한다.
    @Scheduled(cron = "0 * 12-23 * * *", zone = "Asia/Seoul")
    void collect() {
        LocalDate day = LocalDate.now(clock);
        if (store.claim(day)) {
            store.completeLookup(day, bank.fetch(day));
        }
    }
}
