package org.sopt.hashi.restaurant.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.sopt.hashi.exchangerate.ExchangeRatePort.Quote;
import org.sopt.hashi.restaurant.domain.PriceCurrency;

public record MenuWonEstimate(BigDecimal amount, LocalDate appliedDate, LocalDate quotedDate, BigDecimal krwPer100Jpy) {
    public static MenuWonEstimate from(PriceCurrency currency, Long price, Optional<Quote> rate) {
        if (currency != PriceCurrency.JPY || price == null || rate.isEmpty()) {
            return null;
        }
        Quote quote = rate.get();
        return new MenuWonEstimate(quote.toWon(BigDecimal.valueOf(price)), quote.appliedDate(), quote.quotedDate(), quote.krwPer100Jpy());
    }
}
