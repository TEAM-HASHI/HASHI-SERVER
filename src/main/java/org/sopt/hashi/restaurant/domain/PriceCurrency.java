package org.sopt.hashi.restaurant.domain;

import java.util.Arrays;
import java.util.Optional;

/** 식당·메뉴 가격에 사용하는 ISO 4217 통화 코드. */
public enum PriceCurrency {

    JPY,
    KRW,
    USD;

    public static Optional<PriceCurrency> from(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(currency -> currency.name().equalsIgnoreCase(value))
                .findFirst();
    }

    public String value() {
        return name();
    }
}
