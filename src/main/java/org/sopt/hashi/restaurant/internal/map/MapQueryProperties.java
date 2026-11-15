package org.sopt.hashi.restaurant.internal.map;

import lombok.Getter;
import lombok.Setter;
import org.sopt.hashi.restaurant.code.RestaurantErrorCode;
import org.sopt.hashi.restaurant.domain.MapQueryBounds;
import org.sopt.hashi.shared.error.BusinessException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 문자열로 바인딩한 뒤 지도 요청에서 검증해 잘못된 지도 설정이 서버 시작을 막지 않게 한다. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "hashi.restaurant.map")
public class MapQueryProperties {

    private Bounds initialBounds = new Bounds();
    private Bounds supportedBounds = new Bounds();

    public Configuration requireConfiguration() {
        try {
            MapQueryBounds initial = initialBounds.parse();
            MapQueryBounds supported = supportedBounds.parse();
            initial.validateQueryWithin(supported);
            return new Configuration(initial, supported);
        } catch (BusinessException | NullPointerException exception) {
            throw new BusinessException(RestaurantErrorCode.MAP_CONFIGURATION_UNAVAILABLE);
        }
    }

    @Getter
    @Setter
    public static class Bounds {
        private String south;
        private String north;
        private String west;
        private String east;

        private MapQueryBounds parse() {
            return MapQueryBounds.parse(south, north, west, east);
        }
    }

    public record Configuration(MapQueryBounds initialBounds, MapQueryBounds supportedBounds) {
    }
}
