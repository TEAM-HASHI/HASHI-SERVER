package org.sopt.hashi.restaurant.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.sopt.hashi.restaurant.domain.MapCoordinates;
import org.sopt.hashi.restaurant.internal.map.GeocodingCandidate;
import org.sopt.hashi.restaurant.internal.map.GeocodingCandidate.AddressComponent;
import org.sopt.hashi.restaurant.internal.map.GeocodingCandidate.Granularity;
import org.sopt.hashi.restaurant.internal.map.GeocodingResult.Candidates;
import org.sopt.hashi.restaurant.internal.map.LocationJobProperties;
import org.springframework.stereotype.Service;

/** v1 자동 채택은 일본어의 완전한 도쿄 주소만 지원한다. 번역/유사도/첫 후보 추측은 하지 않는다. */
@Service
public class LocationAdoptionPolicy {
    private static final List<String> ADDRESS_ORDER = List.of("administrative_area_level_1", "locality",
            "sublocality_level_1", "sublocality_level_2", "sublocality_level_3", "sublocality_level_4",
            "route", "street_number");
    private static final Set<String> ALLOWED_TYPES = Set.of("country", "postal_code", "political", "sublocality",
            "administrative_area_level_1", "locality", "sublocality_level_1", "sublocality_level_2",
            "sublocality_level_3", "sublocality_level_4", "route", "street_number");
    private static final Pattern FULL_NUMBER = Pattern.compile("^([^0-9]+)([1-9][0-9]*)-([1-9][0-9]*)-([1-9][0-9]*)$");
    private final LocationJobProperties properties;

    public LocationAdoptionPolicy(LocationJobProperties properties) {
        this.properties = properties;
    }

    public Decision evaluate(String originalAddress, Candidates result) {
        if (result.candidates().size() != 1) {
            return Decision.review("AMBIGUOUS_RESULTS");
        }
        GeocodingCandidate candidate = result.candidates().getFirst();
        if (!validCoordinate(candidate.latitude(), 90) || !validCoordinate(candidate.longitude(), 180)) {
            return Decision.review("INVALID_COORDINATES");
        }
        if (!"JP".equals(candidate.countryCode())) {
            return Decision.review("COUNTRY_MISMATCH");
        }
        if (!"東京都".equals(candidate.administrativeArea()) || !insideSupportedBounds(candidate)) {
            return Decision.review("OUTSIDE_SUPPORTED_AREA");
        }
        boolean addressPoint = candidate.types().contains("street_address") || candidate.types().contains("premise");
        if (candidate.granularity() != Granularity.ROOFTOP || !addressPoint) {
            return Decision.review("INSUFFICIENT_PRECISION");
        }
        if (!matchesAddress(originalAddress, candidate.addressComponents())) {
            return Decision.review("ADDRESS_MISMATCH");
        }
        MapCoordinates coordinates = MapCoordinates.of(candidate.latitude().setScale(6, RoundingMode.HALF_UP),
                candidate.longitude().setScale(6, RoundingMode.HALF_UP));
        return new Decision(coordinates, null);
    }

    private boolean matchesAddress(String original, List<AddressComponent> components) {
        Map<String, String> values = new HashMap<>();
        for (AddressComponent component : components) {
            if (component.types().stream().anyMatch(type -> !ALLOWED_TYPES.contains(type))) {
                return false;
            }
            for (String type : component.types()) {
                if (ADDRESS_ORDER.contains(type) || "country".equals(type) || "postal_code".equals(type)) {
                    String text = "country".equals(type) ? component.shortText() : component.longText();
                    if (text == null || text.isBlank() || values.putIfAbsent(type, text) != null) {
                        return false;
                    }
                }
            }
        }
        boolean hasHierarchy = "JP".equals(values.get("country"))
                && "東京都".equals(values.get("administrative_area_level_1"))
                && (values.containsKey("locality") || values.containsKey("sublocality_level_1"));
        if (!hasHierarchy) {
            return false;
        }
        StringBuilder structured = new StringBuilder();
        for (String type : ADDRESS_ORDER) {
            String value = values.get(type);
            if (value != null) {
                // Numeric component boundaries are explicit; no substring/address similarity comparison.
                if (!structured.isEmpty() && Character.isDigit(structured.charAt(structured.length() - 1))
                        && Character.isDigit(value.charAt(0))) {
                    structured.append('-');
                }
                structured.append(value);
            }
        }
        String canonical = normalize(structured.toString());
        String input = normalize(original);
        if (input.startsWith("日本")) {
            input = input.substring(2);
        }
        String postal = values.get("postal_code");
        if (postal != null && input.startsWith("〒" + normalize(postal))) {
            input = input.substring(normalize(postal).length() + 1);
        }
        return FULL_NUMBER.matcher(canonical).matches() && canonical.equals(input);
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("[\\p{javaWhitespace}\\p{Zs}]", "")
                .replaceAll("[‐‑−－]", "-")
                .replaceAll("([0-9]+)丁目", "$1-")
                .replaceAll("([0-9]+)番地?", "$1-")
                .replaceAll("([0-9]+)号$", "$1");
    }

    private boolean insideSupportedBounds(GeocodingCandidate candidate) {
        return properties.south() != null && properties.north() != null
                && properties.west() != null && properties.east() != null
                && candidate.latitude().compareTo(properties.south()) >= 0
                && candidate.latitude().compareTo(properties.north()) <= 0
                && candidate.longitude().compareTo(properties.west()) >= 0
                && candidate.longitude().compareTo(properties.east()) <= 0;
    }

    private static boolean validCoordinate(BigDecimal value, int maximum) {
        return value != null && value.abs().compareTo(BigDecimal.valueOf(maximum)) <= 0;
    }

    public record Decision(MapCoordinates coordinates, String failureCode) {
        static Decision review(String code) {
            return new Decision(null, code);
        }

        @Override
        public String toString() {
            return "LocationDecision[failureCode=" + failureCode + "]";
        }
    }
}
