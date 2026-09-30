package org.sopt.hashi.restaurant.internal.map.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.sopt.hashi.restaurant.internal.map.GeocodingCandidate.Granularity;
import org.sopt.hashi.restaurant.internal.map.GeocodingResult;
import org.sopt.hashi.restaurant.internal.map.GeocodingResult.Candidates;
import org.sopt.hashi.restaurant.internal.map.GeocodingResult.Failure;
import org.sopt.hashi.restaurant.internal.map.GeocodingResult.FailureKind;
import org.sopt.hashi.restaurant.internal.map.GeocodingResult.NoResults;

class GoogleGeocodingResponseParserTest {

    private final GoogleGeocodingResponseParser parser = new GoogleGeocodingResponseParser();

    @Test
    void 좌표_정밀도와_채택에_필요한_메타데이터를_보존한다() {
        Candidates result = (Candidates) parse(GeocodingFixtures.SUCCESS);
        var candidate = result.candidates().getFirst();
        assertThat(candidate.latitude()).isEqualByComparingTo("35.12345678901234567");
        assertThat(candidate.longitude()).isEqualByComparingTo("139.76543210987654321");
        assertThat(candidate.granularity()).isEqualTo(Granularity.ROOFTOP);
        assertThat(candidate.countryCode()).isEqualTo("JP");
        assertThat(candidate.administrativeArea()).isEqualTo("東京都");
        assertThat(candidate.addressComponents().getFirst().longText()).isEqualTo("架空住所");
        assertThat(candidate.addressComponents().getFirst().types()).containsExactly("route");
        assertThat(candidate.types()).containsExactly("street_address");
        assertThatThrownBy(() -> result.candidates().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> candidate.addressComponents().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void 복수_후보와_다른_국가를_그대로_반환하고_첫_항목을_채택하지_않는다() {
        String foreign = GeocodingFixtures.CANDIDATE.replace("JP", "US").replace("ROOFTOP", "APPROXIMATE");
        Candidates result = (Candidates) parse("{\"results\":[" + GeocodingFixtures.CANDIDATE + "," + foreign + "]}");
        assertThat(result.candidates()).hasSize(2);
        assertThat(result.candidates().getLast().countryCode()).isEqualTo("US");
        assertThat(result.candidates().getLast().granularity()).isEqualTo(Granularity.APPROXIMATE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"results\":[]}", "{\"futureMetadata\":true}"})
    void 생략된_repeated_results와_명시적_빈_배열은_0건이다(String body) {
        assertThat(parse(body)).isEqualTo(new NoResults());
    }

    @ParameterizedTest
    @CsvSource({"0,0", "90,180", "-90,-180", "0,-180"})
    void 영점과_전세계_좌표_경계는_유효하다(String latitude, String longitude) {
        Candidates result = (Candidates) parse(withLocation(latitude, longitude));
        assertThat(result.candidates().getFirst().latitude()).isEqualByComparingTo(latitude);
        assertThat(result.candidates().getFirst().longitude()).isEqualByComparingTo(longitude);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROOFTOP", "RANGE_INTERPOLATED", "GEOMETRIC_CENTER", "APPROXIMATE"})
    void v4_정확도_종류를_보존한다(String granularity) {
        Candidates result = (Candidates) parse(GeocodingFixtures.SUCCESS.replace("ROOFTOP", granularity));
        assertThat(result.candidates().getFirst().granularity()).isEqualTo(Granularity.valueOf(granularity));
    }

    @Test
    void 누락된_메타데이터나_새_정확도는_기본_정확도로_승격하지_않는다() {
        Candidates missing = (Candidates) parse(withLocation("1", "2"));
        assertThat(missing.candidates().getFirst().granularity()).isEqualTo(Granularity.UNKNOWN);
        assertThat(missing.candidates().getFirst().countryCode()).isEmpty();
        assertThat(missing.candidates().getFirst().addressComponents()).isEmpty();
        Candidates unknown = (Candidates) parse(GeocodingFixtures.SUCCESS.replace("ROOFTOP", "FUTURE_VALUE"));
        assertThat(unknown.candidates().getFirst().granularity()).isEqualTo(Granularity.UNKNOWN);
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void 잘못된_JSON과_좌표_누락을_0건이나_영점으로_변환하지_않는다(String body) {
        assertThat(parse(body)).isEqualTo(new Failure(FailureKind.INVALID_RESPONSE, 200));
    }

    static Stream<String> invalidBodies() {
        return Stream.of("", " ", "null", "[]", "true", "1", "\"text\"", "{", "{}{}", "{}garbage",
                "{\"results\":null}", "{\"results\":{}}", "{\"results\":[null]}", "{\"results\":[{}]}",
                "{\"results\":[],\"results\":[]}", "{\"error\":{\"message\":\"sensitive\"}}",
                "{\"status\":\"ZERO_RESULTS\"}", "{\"error_message\":\"sensitive\"}",
                withLocation("90.000001", "0"), withLocation("0", "-180.000001"),
                withLocation("NaN", "0"), withLocation("\"NaN\"", "0"), withLocation("\"Infinity\"", "0"),
                withLocation("1e9999", "0"), withLocation("\"35\"", "0"), withLocation("null", "0"),
                withLocation("true", "0"), withLocation("{}", "0"),
                "{\"results\":[{\"location\":{\"latitude\":0}}]}",
                "{\"results\":[{\"location\":{\"longitude\":0}}]}",
                GeocodingFixtures.SUCCESS.replace("\"ROOFTOP\"", "7"),
                GeocodingFixtures.SUCCESS.replace("\"regionCode\":\"JP\"", "\"regionCode\":null"),
                GeocodingFixtures.SUCCESS.replace("[\"street_address\"]", "[null]"),
                GeocodingFixtures.SUCCESS.replace("[\"street_address\"]", "{}"),
                "{\"results\":[" + GeocodingFixtures.CANDIDATE + ",{}]}",
                "{\"nested\":" + "[".repeat(25) + "0" + "]".repeat(25) + "}",
                "{\"text\":\"" + "a".repeat(10_000) + "\"}");
    }

    @Test
    void 후보와_주소_구성요소의_toString은_원문과_좌표를_노출하지_않는다() {
        Candidates result = (Candidates) parse(GeocodingFixtures.SUCCESS);
        assertThat(result.toString()).isEqualTo("GeocodingCandidates[redacted]");
        assertThat(result.candidates().getFirst().toString()).isEqualTo("GeocodingCandidate[redacted]");
        assertThat(result.candidates().getFirst().addressComponents().toString()).doesNotContain("架空", "日本");
    }

    private GeocodingResult parse(String body) {
        return parser.parse(body.getBytes(StandardCharsets.UTF_8));
    }

    private static String withLocation(String latitude, String longitude) {
        return "{\"results\":[{\"location\":{\"latitude\":" + latitude + ",\"longitude\":" + longitude + "}}]}";
    }
}
