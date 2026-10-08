package org.sopt.hashi.restaurant.service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.sopt.hashi.restaurant.domain.MapCoordinates;
import org.sopt.hashi.restaurant.internal.map.GeocodingCandidate;
import org.sopt.hashi.restaurant.internal.map.GeocodingCandidate.AddressComponent;
import org.sopt.hashi.restaurant.internal.map.GeocodingCandidate.Granularity;
import org.sopt.hashi.restaurant.internal.map.GeocodingResult.Candidates;
import org.sopt.hashi.restaurant.internal.map.LocationJobProperties;
import org.springframework.stereotype.Service;

/** 도쿄의 유일한 rooftop 후보만 채택한다. 주소 문자열은 확실한 우편번호·본번 충돌을 막는 데만 사용한다. */
@Service
public class LocationAdoptionPolicy {
    private static final Set<String> SUPPORTED_ADMINISTRATIVE_AREAS = Set.of("東京都", "Tokyo");
    private static final Pattern POSTAL_CODE = Pattern.compile(
            "(?<![0-9])(?:〒\\s*)?([0-9]{3})-?([0-9]{4})(?![0-9])");
    private static final Pattern FLOOR = Pattern.compile(
            "(?:地下\\s*)?[Bb]?\\s*[0-9]+\\s*(?:F|階)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern MAIN_NUMBER_SEQUENCE = Pattern.compile(
            "(?<![0-9])([1-9][0-9]*(?:-[1-9][0-9]*){1,2})(?![-0-9])");
    private static final Pattern CHOME = Pattern.compile(
            "^([1-9][0-9]*)\\s*-?\\s*(?:丁目|ch[oō]me)$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern BLOCK = Pattern.compile("^([1-9][0-9]*)\\s*番地?$");
    private static final Pattern BUILDING = Pattern.compile("^([1-9][0-9]*)\\s*号$");
    private static final Pattern POSITIVE_NUMBER = Pattern.compile("^[1-9][0-9]*$");

    private final LocationJobProperties properties;

    public LocationAdoptionPolicy(LocationJobProperties properties) {
        this.properties = properties;
    }

    public Decision evaluate(String originalAddress, Candidates result) {
        List<GeocodingCandidate> qualifying = new ArrayList<>();
        String singleFailure = null;
        for (GeocodingCandidate candidate : result.candidates()) {
            String failure = rejectionReason(originalAddress, candidate);
            if (failure == null) {
                if (qualifying.stream().noneMatch(existing -> sameCoordinates(existing, candidate))) {
                    qualifying.add(candidate);
                }
            } else if (result.candidates().size() == 1) {
                singleFailure = failure;
            }
        }
        if (qualifying.size() != 1) {
            return Decision.review(qualifying.isEmpty() && singleFailure != null
                    ? singleFailure : "AMBIGUOUS_RESULTS");
        }
        GeocodingCandidate candidate = qualifying.getFirst();
        MapCoordinates coordinates = MapCoordinates.of(candidate.latitude().setScale(6, RoundingMode.HALF_UP),
                candidate.longitude().setScale(6, RoundingMode.HALF_UP));
        return new Decision(coordinates, null);
    }

    private String rejectionReason(String originalAddress, GeocodingCandidate candidate) {
        if (!validCoordinate(candidate.latitude(), 90) || !validCoordinate(candidate.longitude(), 180)) {
            return "INVALID_COORDINATES";
        }
        if (candidate.countryCode() == null || candidate.countryCode().isBlank()) {
            return "COUNTRY_MISSING";
        }
        if (!"JP".equals(candidate.countryCode())) {
            return "COUNTRY_MISMATCH";
        }
        if (!SUPPORTED_ADMINISTRATIVE_AREAS.contains(candidate.administrativeArea())
                || !insideSupportedBounds(candidate)) {
            return "OUTSIDE_SUPPORTED_AREA";
        }
        if (candidate.granularity() != Granularity.ROOFTOP) {
            return "INSUFFICIENT_PRECISION";
        }
        if (hasConflictingPostalCode(originalAddress, candidate.addressComponents())
                || hasConflictingMainNumber(originalAddress, candidate.addressComponents())) {
            return "ADDRESS_MISMATCH";
        }
        return null;
    }

    private static boolean hasConflictingPostalCode(String input, List<AddressComponent> components) {
        Set<String> inputPostalCodes = postalCodes(input == null ? List.of() : List.of(input));
        List<String> providerPostalValues = components.stream()
                .filter(component -> component.types().contains("postal_code"))
                .flatMap(component -> java.util.stream.Stream.of(component.longText(), component.shortText()))
                .toList();
        Set<String> providerPostalCodes = postalCodes(providerPostalValues);
        if (inputPostalCodes.size() > 1 || providerPostalCodes.size() > 1) {
            return true;
        }
        return inputPostalCodes.size() == 1 && providerPostalCodes.size() == 1
                && !inputPostalCodes.iterator().next().equals(providerPostalCodes.iterator().next());
    }

    private static Set<String> postalCodes(List<String> values) {
        Set<String> postalCodes = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            Matcher matcher = POSTAL_CODE.matcher(normalize(value));
            while (matcher.find()) {
                postalCodes.add(matcher.group(1) + matcher.group(2));
            }
        }
        return postalCodes;
    }

    private static boolean hasConflictingMainNumber(String input, List<AddressComponent> components) {
        InputNumber inputNumber = inputMainNumber(input);
        ProviderNumber providerNumber = providerMainNumber(components);
        if (inputNumber.conflicting() || providerNumber.conflicting()) {
            return true;
        }
        if (inputNumber.evidence().isEmpty() || providerNumber.evidence().isEmpty()) {
            return false;
        }
        List<BigInteger> inputParts = inputNumber.evidence().orElseThrow();
        NumberEvidence provider = providerNumber.evidence().orElseThrow();
        if (provider.fullTuple()) {
            return !inputParts.equals(provider.parts());
        }
        return !endsWith(inputParts, provider.parts());
    }

    private static InputNumber inputMainNumber(String value) {
        if (value == null) {
            return new InputNumber(Optional.empty(), false);
        }
        String normalized = normalize(value);
        normalized = POSTAL_CODE.matcher(normalized).replaceAll(" ");
        normalized = FLOOR.matcher(normalized).replaceAll(" ");
        normalized = normalizeNumberSeparators(normalized);
        Set<List<BigInteger>> matches = numberSequences(normalized);
        if (matches.size() > 1) {
            return new InputNumber(Optional.empty(), true);
        }
        Optional<List<BigInteger>> evidence = matches.size() == 1
                ? Optional.of(matches.iterator().next()) : Optional.empty();
        return new InputNumber(evidence, false);
    }

    private static ProviderNumber providerMainNumber(List<AddressComponent> components) {
        Set<List<BigInteger>> directTuples = new LinkedHashSet<>();
        Set<BigInteger> chome = new LinkedHashSet<>();
        Set<BigInteger> block = new LinkedHashSet<>();
        Set<BigInteger> building = new LinkedHashSet<>();
        for (AddressComponent component : components) {
            if (component.types().contains("subpremise")) {
                continue;
            }
            for (String value : new String[]{component.longText(), component.shortText()}) {
                if (value == null || value.isBlank()) {
                    continue;
                }
                String normalized = normalize(value);
                addMatchedNumber(normalized, CHOME, chome);
                addMatchedNumber(normalized, BLOCK, block);
                addMatchedNumber(normalized, BUILDING, building);
                if (component.types().contains("sublocality_level_4")
                        && POSITIVE_NUMBER.matcher(normalized).matches()) {
                    block.add(new BigInteger(normalized));
                }
                if (component.types().contains("street_number") || component.types().contains("premise")) {
                    String separated = normalizeNumberSeparators(FLOOR.matcher(normalized).replaceAll(" "));
                    directTuples.addAll(numberSequences(separated));
                    if (POSITIVE_NUMBER.matcher(separated).matches()) {
                        building.add(new BigInteger(separated));
                    }
                }
            }
        }
        if (chome.size() > 1 || block.size() > 1 || building.size() > 1 || directTuples.size() > 1) {
            return ProviderNumber.conflictingNumber();
        }
        Optional<NumberEvidence> componentTuple = Optional.empty();
        if (chome.size() == 1 && block.size() == 1 && building.size() == 1) {
            componentTuple = Optional.of(new NumberEvidence(
                    List.of(first(chome), first(block), first(building)), true));
        }
        Optional<NumberEvidence> directEvidence = Optional.empty();
        if (directTuples.size() == 1) {
            List<BigInteger> direct = directTuples.iterator().next();
            if (direct.size() == 3) {
                directEvidence = Optional.of(new NumberEvidence(direct, true));
            } else if (direct.size() == 2 && chome.size() == 1) {
                directEvidence = Optional.of(new NumberEvidence(
                        List.of(first(chome), direct.get(0), direct.get(1)), true));
            } else {
                directEvidence = Optional.of(new NumberEvidence(direct, false));
            }
        }
        if (componentTuple.isPresent() && directEvidence.isPresent()
                && conflictingEvidence(componentTuple.orElseThrow(), directEvidence.orElseThrow())) {
            return ProviderNumber.conflictingNumber();
        }
        if (componentTuple.isPresent()) {
            return new ProviderNumber(componentTuple, false);
        }
        if (directEvidence.isPresent()) {
            return new ProviderNumber(directEvidence, false);
        }
        Optional<NumberEvidence> suffix = building.size() == 1
                ? Optional.of(new NumberEvidence(List.of(first(building)), false))
                : Optional.empty();
        return new ProviderNumber(suffix, false);
    }

    private static boolean conflictingEvidence(NumberEvidence first, NumberEvidence second) {
        if (first.fullTuple() && second.fullTuple()) {
            return !first.parts().equals(second.parts());
        }
        NumberEvidence full = first.fullTuple() ? first : second;
        NumberEvidence suffix = first.fullTuple() ? second : first;
        return !endsWith(full.parts(), suffix.parts());
    }

    private static void addMatchedNumber(String value, Pattern pattern, Set<BigInteger> target) {
        Matcher matcher = pattern.matcher(value);
        if (matcher.matches()) {
            target.add(new BigInteger(matcher.group(1)));
        }
    }

    private static Set<List<BigInteger>> numberSequences(String value) {
        Set<List<BigInteger>> matches = new LinkedHashSet<>();
        Matcher matcher = MAIN_NUMBER_SEQUENCE.matcher(value);
        while (matcher.find()) {
            matches.add(List.of(matcher.group(1).split("-")).stream()
                    .map(BigInteger::new).toList());
        }
        return matches;
    }

    private static String normalizeNumberSeparators(String value) {
        return value.replaceAll("(?i)([1-9][0-9]*)\\s*-?\\s*(?:丁目|ch[oō]me)\\s*-?", "$1-")
                .replaceAll("([1-9][0-9]*)\\s*番地?\\s*-?", "$1-")
                .replaceAll("([1-9][0-9]*)\\s*号", "$1");
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).replaceAll("[‐‑−－ー]", "-");
    }

    private static boolean endsWith(List<BigInteger> whole, List<BigInteger> suffix) {
        if (whole.size() < suffix.size()) {
            return false;
        }
        int offset = whole.size() - suffix.size();
        for (int index = 0; index < suffix.size(); index++) {
            if (!whole.get(offset + index).equals(suffix.get(index))) {
                return false;
            }
        }
        return true;
    }

    private static BigInteger first(Set<BigInteger> values) {
        return values.iterator().next();
    }

    private static boolean sameCoordinates(GeocodingCandidate left, GeocodingCandidate right) {
        return left.latitude().compareTo(right.latitude()) == 0
                && left.longitude().compareTo(right.longitude()) == 0;
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

    private record NumberEvidence(List<BigInteger> parts, boolean fullTuple) {
        private NumberEvidence {
            parts = List.copyOf(parts);
        }
    }

    private record ProviderNumber(Optional<NumberEvidence> evidence, boolean conflicting) {
        private static ProviderNumber conflictingNumber() {
            return new ProviderNumber(Optional.empty(), true);
        }
    }

    private record InputNumber(Optional<List<BigInteger>> evidence, boolean conflicting) {
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
