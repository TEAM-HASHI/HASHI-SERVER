package org.sopt.hashi.restaurant.internal.map.places;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.sopt.hashi.restaurant.internal.map.GeocodingResult.FailureKind;

class GooglePlacesResponseParserTest {

    private final GooglePlacesResponseParser parser = new GooglePlacesResponseParser();

    @Test
    void 검색과_상세에서_장소_식별정보와_공식_attribution을_보존한다() {
        PlacesSearchResult.Candidates search = (PlacesSearchResult.Candidates) parseSearch(
                PlacesFixtures.SEARCH_SUCCESS);
        PlaceDetailsResult.Place details = (PlaceDetailsResult.Place) parseDetails(PlacesFixtures.CANDIDATE);

        PlacesCandidate candidate = search.candidates().getFirst();
        assertThat(details.candidate()).isEqualTo(candidate);
        assertThat(candidate.placeId()).isEqualTo(PlacesFixtures.PLACE_ID);
        assertThat(candidate.displayName()).isEqualTo("Synthetic Restaurant");
        assertThat(candidate.address()).isEqualTo("1-2-3 Test, Chiyoda City, Tokyo");
        assertThat(candidate.latitude()).isEqualByComparingTo("35.12345678901234567");
        assertThat(candidate.longitude()).isEqualByComparingTo("139.76543210987654321");
        assertThat(candidate.countryCode()).isEqualTo("JP");
        assertThat(candidate.administrativeArea()).isEqualTo("Tokyo");
        assertThat(candidate.types()).containsExactly("restaurant", "food");
        assertThat(candidate.businessStatus()).isEqualTo("OPERATIONAL");
        assertThat(candidate.attributions()).containsExactly(
                new PlacesAttribution("Synthetic Provider", "https://provider.invalid/info"));
        assertThat(candidate.googleMapsUri()).isEqualTo("https://maps.google.com/?cid=synthetic");
    }

    @Test
    void 생략되거나_빈_places는_검색결과_없음이다() {
        assertThat(parseSearch("{}")).isEqualTo(new PlacesSearchResult.NoResults());
        assertThat(parseSearch("{\"places\":[]}"))
                .isEqualTo(new PlacesSearchResult.NoResults());
    }

    @ParameterizedTest
    @MethodSource("invalidSearchBodies")
    void 잘못된_검색_JSON은_원문없이_실패한다(String body) {
        assertThat(parseSearch(body)).isEqualTo(
                new PlacesSearchResult.Failure(FailureKind.INVALID_RESPONSE, 200));
    }

    @ParameterizedTest
    @MethodSource("invalidDetailsBodies")
    void 잘못된_상세_JSON은_원문없이_실패한다(String body) {
        assertThat(parseDetails(body)).isEqualTo(
                new PlaceDetailsResult.Failure(FailureKind.INVALID_RESPONSE, 200));
    }

    @Test
    void 결과의_목록은_방어적으로_복사되고_toString은_장소정보를_숨긴다() {
        PlacesSearchResult.Candidates result = (PlacesSearchResult.Candidates) parseSearch(
                PlacesFixtures.SEARCH_SUCCESS);
        PlacesCandidate candidate = result.candidates().getFirst();
        assertThatThrownBy(() -> result.candidates().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> candidate.types().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> candidate.attributions().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(result.toString()).isEqualTo("PlacesSearchCandidates[redacted]");
        assertThat(candidate.toString()).isEqualTo("PlacesCandidate[redacted]");
        assertThat(candidate.attributions().getFirst().toString()).isEqualTo("PlacesAttribution[redacted]");
        assertThat(new PlaceDetailsResult.Place(candidate).toString()).isEqualTo("PlaceDetails[redacted]");
    }

    @Test
    void HTTP_403은_canonical_quota_상태만_구분한다() {
        assertThat(parser.classifyForbidden(bytes("{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\"}}")))
                .isEqualTo(FailureKind.QUOTA_EXCEEDED);
        assertThat(parser.classifyForbidden(bytes("{\"error\":{\"status\":\"PERMISSION_DENIED\"}}")))
                .isEqualTo(FailureKind.ACCESS_DENIED);
        assertThat(parser.classifyForbidden(bytes("{\"error\":")))
                .isEqualTo(FailureKind.ACCESS_DENIED);
    }

    static Stream<String> invalidSearchBodies() {
        String sixPlaces = "{\"places\":[" + String.join(",", java.util.Collections.nCopies(6,
                PlacesFixtures.CANDIDATE)) + "]}";
        return Stream.of("", "null", "[]", "{}{}", "{\"places\":null}", "{\"places\":{}}",
                "{\"places\":[{}]}", "{\"error\":{\"message\":\"private\"}}", sixPlaces,
                PlacesFixtures.SEARCH_SUCCESS.replace("35.12345678901234567", "90.000001"),
                PlacesFixtures.SEARCH_SUCCESS.replace("\"provider\":\"Synthetic Provider\"", "\"provider\":null"),
                "{\"nested\":" + "[".repeat(25) + "0" + "]".repeat(25) + "}",
                "{\"text\":\"" + "a".repeat(10_000) + "\"}");
    }

    static Stream<String> invalidDetailsBodies() {
        return Stream.of("", "{}", "null", "[]", "{}{}", "{\"error\":{}}",
                PlacesFixtures.CANDIDATE.replace("\"id\":\"ChIJ_synthetic-place-1\"", "\"id\":null"),
                PlacesFixtures.CANDIDATE.replace("139.76543210987654321", "180.000001"),
                PlacesFixtures.CANDIDATE.replace("\"formattedAddress\":\"1-2-3 Test, Chiyoda City, Tokyo\"",
                        "\"formattedAddress\":{}"));
    }

    private PlacesSearchResult parseSearch(String body) {
        return parser.parseSearch(bytes(body));
    }

    private PlaceDetailsResult parseDetails(String body) {
        return parser.parseDetails(bytes(body));
    }

    private static byte[] bytes(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }
}
