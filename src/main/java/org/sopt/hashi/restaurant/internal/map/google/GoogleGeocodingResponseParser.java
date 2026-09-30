package org.sopt.hashi.restaurant.internal.map.google;

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
import org.sopt.hashi.restaurant.internal.map.GeocodingCandidate;
import org.sopt.hashi.restaurant.internal.map.GeocodingCandidate.AddressComponent;
import org.sopt.hashi.restaurant.internal.map.GeocodingCandidate.Granularity;
import org.sopt.hashi.restaurant.internal.map.GeocodingResult;
import org.sopt.hashi.restaurant.internal.map.GeocodingResult.Candidates;
import org.sopt.hashi.restaurant.internal.map.GeocodingResult.Failure;
import org.sopt.hashi.restaurant.internal.map.GeocodingResult.FailureKind;
import org.sopt.hashi.restaurant.internal.map.GeocodingResult.NoResults;

final class GoogleGeocodingResponseParser {

    private final ObjectMapper mapper = JsonMapper.builder(JsonFactory.builder()
                    .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
                    .streamReadConstraints(StreamReadConstraints.builder()
                            .maxNestingDepth(20).maxNumberLength(64).maxStringLength(8192).build())
                    .build())
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .build();

    GeocodingResult parse(byte[] body) {
        try {
            JsonNode root = mapper.readTree(body);
            require(root != null && root.isObject());
            require(!root.has("error") && !root.has("status") && !root.has("error_message"));
            JsonNode results = root.get("results");
            // ProtoJSON omits an empty repeated field. A valid {} is distinct from an empty/invalid body.
            if (results == null) {
                return new NoResults();
            }
            require(results.isArray());
            if (results.isEmpty()) {
                return new NoResults();
            }
            List<GeocodingCandidate> candidates = new ArrayList<>();
            for (JsonNode result : results) {
                candidates.add(parseCandidate(result));
            }
            return new Candidates(candidates);
        } catch (IOException | RuntimeException ignored) {
            // Parser exceptions may contain response fragments. Never propagate or log the cause.
            return new Failure(FailureKind.INVALID_RESPONSE, 200);
        }
    }

    private GeocodingCandidate parseCandidate(JsonNode result) {
        require(result.isObject());
        JsonNode location = result.path("location");
        require(location.isObject());
        BigDecimal latitude = coordinate(location.get("latitude"), 90);
        BigDecimal longitude = coordinate(location.get("longitude"), 180);
        JsonNode postal = result.path("postalAddress");
        require(postal.isMissingNode() || postal.isObject());
        List<AddressComponent> components = new ArrayList<>();
        JsonNode componentNodes = result.path("addressComponents");
        require(componentNodes.isMissingNode() || componentNodes.isArray());
        for (JsonNode component : componentNodes) {
            require(component.isObject());
            components.add(new AddressComponent(text(component, "longText"), text(component, "shortText"),
                    strings(component.path("types"))));
        }
        return new GeocodingCandidate(latitude, longitude, granularity(text(result, "granularity")),
                text(postal, "regionCode"), text(postal, "administrativeArea"), components,
                strings(result.path("types")));
    }

    private BigDecimal coordinate(JsonNode node, int limit) {
        require(node != null && node.isNumber());
        BigDecimal value = node.decimalValue();
        require(value.abs().compareTo(BigDecimal.valueOf(limit)) <= 0);
        return value;
    }

    private String text(JsonNode object, String name) {
        JsonNode value = object.path(name);
        if (value.isMissingNode()) {
            return "";
        }
        require(value.isTextual());
        return value.textValue();
    }

    private List<String> strings(JsonNode values) {
        require(values.isMissingNode() || values.isArray());
        List<String> result = new ArrayList<>();
        for (JsonNode value : values) {
            require(value.isTextual());
            result.add(value.textValue());
        }
        return List.copyOf(result);
    }

    private Granularity granularity(String value) {
        return switch (value) {
            case "ROOFTOP" -> Granularity.ROOFTOP;
            case "RANGE_INTERPOLATED" -> Granularity.RANGE_INTERPOLATED;
            case "GEOMETRIC_CENTER" -> Granularity.GEOMETRIC_CENTER;
            case "APPROXIMATE" -> Granularity.APPROXIMATE;
            default -> Granularity.UNKNOWN;
        };
    }

    private void require(boolean valid) {
        if (!valid) {
            throw new IllegalArgumentException("Invalid geocoding response");
        }
    }
}
