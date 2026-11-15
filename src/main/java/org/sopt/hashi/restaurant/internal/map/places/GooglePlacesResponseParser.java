package org.sopt.hashi.restaurant.internal.map.places;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.sopt.hashi.restaurant.internal.map.GeocodingResult.FailureKind;

final class GooglePlacesResponseParser {

    private static final int MAX_SEARCH_CANDIDATES = 5;
    private static final int MAX_TYPES = 64;
    private static final int MAX_ATTRIBUTIONS = 32;
    private static final int MAX_SHORT_TEXT_LENGTH = 1024;
    private static final int MAX_LONG_TEXT_LENGTH = 8192;
    private static final int MAX_URI_LENGTH = 4096;
    private static final Pattern PLACE_ID = Pattern.compile("[A-Za-z0-9._~-]{1,255}");

    private final ObjectMapper mapper = JsonMapper.builder(JsonFactory.builder()
                    .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
                    .streamReadConstraints(StreamReadConstraints.builder()
                            .maxNestingDepth(20).maxNumberLength(64).maxStringLength(MAX_LONG_TEXT_LENGTH).build())
                    .build())
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .build();

    PlacesSearchResult parseSearch(byte[] body) {
        try {
            JsonNode root = parseRoot(body);
            JsonNode places = root.get("places");
            if (places == null) {
                return new PlacesSearchResult.NoResults();
            }
            require(places.isArray() && places.size() <= MAX_SEARCH_CANDIDATES);
            if (places.isEmpty()) {
                return new PlacesSearchResult.NoResults();
            }
            List<PlacesCandidate> candidates = new ArrayList<>();
            for (JsonNode place : places) {
                candidates.add(parseCandidate(place));
            }
            return new PlacesSearchResult.Candidates(candidates);
        } catch (IOException | RuntimeException ignored) {
            return new PlacesSearchResult.Failure(FailureKind.INVALID_RESPONSE, 200);
        }
    }

    PlaceDetailsResult parseDetails(byte[] body) {
        try {
            return new PlaceDetailsResult.Place(parseCandidate(parseRoot(body)));
        } catch (IOException | RuntimeException ignored) {
            return new PlaceDetailsResult.Failure(FailureKind.INVALID_RESPONSE, 200);
        }
    }

    FailureKind classifyForbidden(byte[] body) {
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode status = root == null ? null : root.path("error").path("status");
            if (status == null || !status.isTextual()) {
                return FailureKind.ACCESS_DENIED;
            }
            return "RESOURCE_EXHAUSTED".equals(status.textValue())
                    ? FailureKind.QUOTA_EXCEEDED : FailureKind.ACCESS_DENIED;
        } catch (IOException | RuntimeException ignored) {
            return FailureKind.ACCESS_DENIED;
        }
    }

    private JsonNode parseRoot(byte[] body) throws IOException {
        JsonNode root = mapper.readTree(body);
        require(root != null && root.isObject());
        require(!root.has("error") && !root.has("status") && !root.has("error_message"));
        return root;
    }

    private PlacesCandidate parseCandidate(JsonNode place) {
        require(place.isObject());
        String placeId = requiredText(place, "id", 255);
        require(PLACE_ID.matcher(placeId).matches());
        JsonNode displayName = place.path("displayName");
        require(displayName.isObject());
        String name = requiredText(displayName, "text", MAX_SHORT_TEXT_LENGTH);
        String address = requiredText(place, "formattedAddress", MAX_LONG_TEXT_LENGTH);
        JsonNode location = place.path("location");
        require(location.isObject());
        BigDecimal latitude = coordinate(location.get("latitude"), 90);
        BigDecimal longitude = coordinate(location.get("longitude"), 180);
        AddressRegion region = parseRegion(place.path("addressComponents"));
        List<String> types = strings(place.path("types"), MAX_TYPES, MAX_SHORT_TEXT_LENGTH);
        String businessStatus = optionalText(place, "businessStatus", MAX_SHORT_TEXT_LENGTH);
        List<PlacesAttribution> attributions = parseAttributions(place.path("attributions"));
        String googleMapsUri = requiredText(place, "googleMapsUri", MAX_URI_LENGTH);
        return new PlacesCandidate(placeId, name, address, latitude, longitude, region.countryCode(),
                region.administrativeArea(), types, businessStatus, attributions, googleMapsUri);
    }

    private AddressRegion parseRegion(JsonNode components) {
        require(components.isMissingNode() || components.isArray());
        String countryCode = "";
        String administrativeArea = "";
        int count = 0;
        for (JsonNode component : components) {
            require(++count <= MAX_TYPES && component.isObject());
            List<String> types = strings(component.path("types"), MAX_TYPES, MAX_SHORT_TEXT_LENGTH);
            if (types.contains("country") && countryCode.isEmpty()) {
                countryCode = optionalText(component, "shortText", MAX_SHORT_TEXT_LENGTH);
            }
            if (types.contains("administrative_area_level_1") && administrativeArea.isEmpty()) {
                administrativeArea = optionalText(component, "longText", MAX_SHORT_TEXT_LENGTH);
            }
        }
        return new AddressRegion(countryCode, administrativeArea);
    }

    private List<PlacesAttribution> parseAttributions(JsonNode values) {
        require(values.isMissingNode() || values.isArray());
        List<PlacesAttribution> result = new ArrayList<>();
        for (JsonNode value : values) {
            require(result.size() < MAX_ATTRIBUTIONS && value.isObject());
            result.add(new PlacesAttribution(requiredText(value, "provider", MAX_SHORT_TEXT_LENGTH),
                    requiredText(value, "providerUri", MAX_URI_LENGTH)));
        }
        return List.copyOf(result);
    }

    private BigDecimal coordinate(JsonNode node, int limit) {
        require(node != null && node.isNumber());
        BigDecimal value = node.decimalValue();
        require(value.abs().compareTo(BigDecimal.valueOf(limit)) <= 0);
        return value;
    }

    private String requiredText(JsonNode object, String name, int maximumLength) {
        String value = optionalText(object, name, maximumLength);
        require(!value.isBlank());
        return value;
    }

    private String optionalText(JsonNode object, String name, int maximumLength) {
        JsonNode value = object.path(name);
        if (value.isMissingNode()) {
            return "";
        }
        require(value.isTextual() && value.textValue().length() <= maximumLength);
        return value.textValue();
    }

    private List<String> strings(JsonNode values, int maximumSize, int maximumTextLength) {
        require(values.isMissingNode() || values.isArray());
        List<String> result = new ArrayList<>();
        for (JsonNode value : values) {
            require(result.size() < maximumSize && value.isTextual()
                    && value.textValue().length() <= maximumTextLength);
            result.add(value.textValue());
        }
        return List.copyOf(result);
    }

    private void require(boolean valid) {
        if (!valid) {
            throw new IllegalArgumentException("Invalid Google Places response");
        }
    }

    private record AddressRegion(String countryCode, String administrativeArea) {
    }
}
