package org.sopt.hashi.exchangerate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.domain.Page;

public interface ExchangeRatePort {
    record Quote(LocalDate appliedDate, LocalDate quotedDate, BigDecimal krwPer100Jpy) {
        public BigDecimal toWon(BigDecimal yen) {
            return yen.multiply(krwPer100Jpy).movePointLeft(2).setScale(0, RoundingMode.HALF_UP);
        }
    }

    record Revision(long id, Quote quote, String source, Long adminId, LocalDateTime createdAtUtc) {}

    Optional<Quote> current();
    Revision correct(LocalDate appliedDate, LocalDate quotedDate, BigDecimal krwPer100Jpy);
    Page<Revision> history(int page, int size);
}
