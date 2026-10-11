package org.sopt.hashi.restaurant.domain;

import java.math.BigDecimal;
import org.sopt.hashi.restaurant.code.RestaurantErrorCode;
import org.sopt.hashi.shared.error.BusinessException;

/** 조회 경계는 저장 좌표와 달리 SDK의 정밀도를 그대로 유지한다. 반올림하거나 절삭하지 않는다. */
public record MapQueryBounds(BigDecimal south, BigDecimal north, BigDecimal west, BigDecimal east) {

    public MapQueryBounds {
        boolean valid = inRange(south, 90) && inRange(north, 90)
                && inRange(west, 180) && inRange(east, 180)
                && south.compareTo(north) < 0 && west.compareTo(east) < 0;
        if (!valid) {
            throw new BusinessException(RestaurantErrorCode.MAP_BOUNDS_INVALID);
        }
    }

    public static MapQueryBounds parse(String south, String north, String west, String east) {
        try {
            return new MapQueryBounds(new BigDecimal(south), new BigDecimal(north),
                    new BigDecimal(west), new BigDecimal(east));
        } catch (NumberFormatException | NullPointerException exception) {
            throw new BusinessException(RestaurantErrorCode.MAP_BOUNDS_INVALID);
        }
    }

    public void validateQueryWithin(MapQueryBounds supported) {
        boolean allowed = north.subtract(south).compareTo(BigDecimal.ONE) <= 0
                && east.subtract(west).compareTo(BigDecimal.ONE) <= 0 && supported.contains(this);
        if (!allowed) {
            throw new BusinessException(RestaurantErrorCode.MAP_BOUNDS_INVALID);
        }
    }

    public boolean contains(MapQueryBounds other) {
        return other.south.compareTo(south) >= 0 && other.north.compareTo(north) <= 0
                && other.west.compareTo(west) >= 0 && other.east.compareTo(east) <= 0;
    }

    private static boolean inRange(BigDecimal value, int maximum) {
        return value != null && value.abs().compareTo(BigDecimal.valueOf(maximum)) <= 0;
    }
}
