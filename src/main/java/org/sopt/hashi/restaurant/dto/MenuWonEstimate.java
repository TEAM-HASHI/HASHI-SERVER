package org.sopt.hashi.restaurant.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.sopt.hashi.exchangerate.ExchangeRatePort.Quote;
import org.sopt.hashi.restaurant.domain.PriceCurrency;
import org.sopt.hashi.restaurant.domain.RestaurantMenu;

public record MenuWonEstimate(BigDecimal amount, LocalDate appliedDate, LocalDate quotedDate, BigDecimal krwPer100Jpy) {
    public static MenuWonEstimate from(RestaurantMenu menu, Optional<Quote> rate) {
        if (menu.getPriceCurrency() != PriceCurrency.JPY || menu.getPriceAmount() == null || rate.isEmpty()) {
            return null;
        }
        Quote quote = rate.get();
        return new MenuWonEstimate(quote.toWon(menu.getPriceAmount()), quote.appliedDate(), quote.quotedDate(), quote.krwPer100Jpy());
    }
}
