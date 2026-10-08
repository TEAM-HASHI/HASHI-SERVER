package org.sopt.hashi.restaurant;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Places 검색 응답의 일회성 후보. provider 원문과 Place ID는 공개 계약에 포함하지 않는다. */
public record RestaurantPlacesCandidateInfo(
        String displayName,
        String address,
        BigDecimal latitude,
        BigDecimal longitude,
        String countryCode,
        String administrativeArea,
        List<String> types,
        String businessStatus,
        List<Attribution> attributions,
        String googleMapsUri,
        String selectionToken,
        Instant selectionExpiresAt) {

    public record Attribution(String displayName, String uri) {
    }
}
