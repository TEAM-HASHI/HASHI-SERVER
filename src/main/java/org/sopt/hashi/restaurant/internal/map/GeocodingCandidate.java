package org.sopt.hashi.restaurant.internal.map;

import java.math.BigDecimal;
import java.util.List;

/** 검증에 필요한 값만 일시적으로 전달한다. Google 원문 응답과 formattedAddress는 보관하지 않는다. */
public record GeocodingCandidate(
        BigDecimal latitude,
        BigDecimal longitude,
        Granularity granularity,
        String countryCode,
        String administrativeArea,
        List<AddressComponent> addressComponents,
        List<String> types
) {

    public GeocodingCandidate {
        addressComponents = List.copyOf(addressComponents);
        types = List.copyOf(types);
    }

    @Override
    public String toString() {
        return "GeocodingCandidate[redacted]";
    }

    public enum Granularity {
        ROOFTOP, RANGE_INTERPOLATED, GEOMETRIC_CENTER, APPROXIMATE, UNKNOWN
    }

    public record AddressComponent(String longText, String shortText, List<String> types) {
        public AddressComponent {
            types = List.copyOf(types);
        }

        @Override
        public String toString() {
            return "GeocodingAddressComponent[redacted]";
        }
    }
}
