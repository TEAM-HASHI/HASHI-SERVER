package org.sopt.hashi.exchangerate.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.sopt.hashi.auth.ActorType;
import org.sopt.hashi.auth.CurrentActorProvider;
import org.sopt.hashi.exchangerate.ExchangeRatePort;
import org.sopt.hashi.exchangerate.domain.ExchangeRateStore;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
class ExchangeRateService implements ExchangeRatePort {
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final ExchangeRateStore store;
    private final CurrentActorProvider actors;
    private final Clock clock;

    ExchangeRateService(ExchangeRateStore store, CurrentActorProvider actors, @Qualifier("japanClock") Clock clock) {
        this.store = store;
        this.actors = actors;
        this.clock = clock.withZone(SEOUL);
    }

    public Optional<Quote> current() {
        return store.latest(LocalDate.now(clock)).map(Revision::quote);
    }

    public Revision correct(LocalDate appliedDate, LocalDate quotedDate, BigDecimal rate) {
        long admin = adminId();
        if (appliedDate.isAfter(LocalDate.now(clock)) || quotedDate.isAfter(appliedDate)
                || rate.signum() <= 0 || rate.scale() > 4 || rate.precision() - rate.scale() > 8) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        return store.correct(appliedDate, quotedDate, rate, admin);
    }

    public List<Revision> history(Long beforeId, int size) {
        adminId();
        return store.history(beforeId, size);
    }

    private long adminId() {
        var actor = actors.currentActor();
        if (actor.type() != ActorType.ADMIN) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        return actor.subjectId();
    }
}
