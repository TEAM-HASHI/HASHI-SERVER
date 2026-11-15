package org.sopt.hashi.restaurant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** WGS84 좌표 쌍. 저장 정밀도와 실제 위치 정확도는 별개이며 암묵적으로 반올림하지 않는다. */
@Getter
@Embeddable
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MapCoordinates {

    private static final int SCALE = 6;
    private static final BigDecimal MAX_LATITUDE = new BigDecimal("90");
    private static final BigDecimal MAX_LONGITUDE = new BigDecimal("180");

    @Column(name = "latitude", precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(name = "longitude", precision = 10, scale = 6)
    private BigDecimal longitude;

    private MapCoordinates(BigDecimal latitude, BigDecimal longitude) {
        this.latitude = requireLatitude(latitude);
        this.longitude = requireLongitude(longitude);
    }

    public static MapCoordinates of(BigDecimal latitude, BigDecimal longitude) {
        return new MapCoordinates(latitude, longitude);
    }

    static BigDecimal requireLatitude(BigDecimal value) {
        return requireCoordinate(value, MAX_LATITUDE);
    }

    static BigDecimal requireLongitude(BigDecimal value) {
        return requireCoordinate(value, MAX_LONGITUDE);
    }

    private static BigDecimal requireCoordinate(BigDecimal value, BigDecimal maximum) {
        if (value == null || value.abs().compareTo(maximum) > 0) {
            throw new IllegalArgumentException("좌표가 허용 범위를 벗어났습니다");
        }
        try {
            return value.setScale(SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("좌표는 소수점 6자리까지 저장할 수 있습니다", exception);
        }
    }
}
