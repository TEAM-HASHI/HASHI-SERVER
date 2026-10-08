package org.sopt.hashi.restaurant.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.sopt.hashi.restaurant.internal.map.GeocodingCandidate;
import org.sopt.hashi.restaurant.internal.map.GeocodingCandidate.AddressComponent;
import org.sopt.hashi.restaurant.internal.map.GeocodingCandidate.Granularity;
import org.sopt.hashi.restaurant.internal.map.GeocodingResult.Candidates;
import org.sopt.hashi.restaurant.internal.map.GeocodingResult.FailureKind;
import org.sopt.hashi.restaurant.internal.map.LocationJobProperties;

class LocationAdoptionPolicyTest {
    static final String ADDRESS = "東京都試験区架空町1丁目2番3号";
    private final LocationAdoptionPolicy policy = new LocationAdoptionPolicy(properties());

    static LocationJobProperties properties() {
        // Synthetic bounds/addresses; these are not operational Tokyo region data.
        return new LocationJobProperties(true, Duration.ofDays(1), new BigDecimal("10"), new BigDecimal("11"),
                new BigDecimal("20"), new BigDecimal("21"), null);
    }

    static GeocodingCandidate candidate() {
        return numericPremiseCandidate("3");
    }

    static GeocodingCandidate numericPremiseCandidate(String premise) {
        return new GeocodingCandidate(new BigDecimal("10.1234567"), new BigDecimal("20.7654321"),
                Granularity.ROOFTOP, "JP", "東京都", List.of(
                component("日本", "JP", "country"),
                component("東京都", "東京都", "administrative_area_level_1"),
                component("試験区", "試験区", "locality"),
                component("架空町", "架空町", "sublocality_level_2"),
                component("1丁目", "1丁目", "sublocality_level_3"),
                component("2", "2", "sublocality_level_4"),
                component(premise, premise, "premise"),
                component("100-0001", "100-0001", "postal_code"),
                component("架空ビル 99F", "99F", "subpremise")),
                List.of("establishment", "point_of_interest", "shopping_mall"));
    }

    static AddressComponent component(String value, String shortValue, String type) {
        return new AddressComponent(value, shortValue, List.of(type));
    }

    @ParameterizedTest
    @ValueSource(strings = {ADDRESS, "東京都試験区架空町1-2-3", "東京都試験区架空町１−２−３ 架空ビル 4F",
            "Tokyo Shiken-ku Kaku-cho 1 Chome-2-3, Building 4F"})
    void 언어와_건물층_표기와_결과type에_관계없이_안전한_rooftop을_채택한다(String address) {
        var decision = policy.evaluate(address, new Candidates(List.of(candidate())));

        assertThat(decision.failureCode()).isNull();
        assertThat(decision.coordinates().getLatitude()).isEqualByComparingTo("10.123457");
        assertThat(decision.coordinates().getLongitude()).isEqualByComparingTo("20.765432");
    }

    @Test
    void subpremise와_알수없는_component는_본번_충돌판정에서_무시한다() {
        var original = candidate();
        var components = new ArrayList<>(original.addressComponents());
        components.add(component("untrusted detail 777", "777", "future_component"));
        var value = copy(original, original.latitude(), original.longitude(), original.granularity(),
                original.countryCode(), original.administrativeArea(), components);

        assertThat(policy.evaluate("東京都試験区架空町1-2-3 別館777 99F",
                new Candidates(List.of(value))).failureCode()).isNull();
    }

    @Test
    void 두구간_주소는_provider가_마지막_본번만_확실히_주면_suffix만_비교한다() {
        var suffix = candidateWithComponents(List.of(
                component("日本", "JP", "country"),
                component("東京都", "東京都", "administrative_area_level_1"),
                component("3", "3", "premise")));

        assertThat(policy.evaluate("東京都試験区架空町2-3", new Candidates(List.of(suffix)))
                .failureCode()).isNull();
        assertThat(policy.evaluate("東京都試験区架空町2-4", new Candidates(List.of(suffix)))
                .failureCode()).isEqualTo("ADDRESS_MISMATCH");
    }

    @ParameterizedTest
    @ValueSource(strings = {"東京都試験区架空町2-2-3", "東京都試験区架空町1-9-3",
            "東京都試験区架空町1-2-4"})
    void 양쪽에서_전체_본번을_확실히_추출하면_어느구간_충돌도_거절한다(String address) {
        assertThat(policy.evaluate(address, new Candidates(List.of(candidate()))).failureCode())
                .isEqualTo("ADDRESS_MISMATCH");
    }

    @Test
    void 영어응답의_Chome과_component계층도_전체tuple로_비교한다() {
        var value = candidateWithComponents(List.of(
                component("日本", "JP", "country"),
                component("東京都", "東京都", "administrative_area_level_1"),
                component("1-chōme", "1-chōme", "sublocality_level_3"),
                component("2", "2", "sublocality_level_4"),
                component("3", "3", "premise")));

        assertThat(policy.evaluate("Tokyo Shiken-ku 1丁目2番3号", new Candidates(List.of(value)))
                .failureCode()).isNull();
        assertThat(policy.evaluate("Tokyo Shiken-ku 2丁目2番3号", new Candidates(List.of(value)))
                .failureCode()).isEqualTo("ADDRESS_MISMATCH");
        assertThat(policy.evaluate("Tokyo Shiken-ku 1丁目9番3号", new Candidates(List.of(value)))
                .failureCode()).isEqualTo("ADDRESS_MISMATCH");
        assertThat(policy.evaluate("Tokyo Shiken-ku 1丁目2番4号", new Candidates(List.of(value)))
                .failureCode()).isEqualTo("ADDRESS_MISMATCH");
    }

    @Test
    void premise에_층표기가_섞이면_본번tuple로_추측하지_않는다() {
        var value = candidateWithComponents(List.of(
                component("日本", "JP", "country"),
                component("東京都", "東京都", "administrative_area_level_1"),
                component("6-chōme", "6-chōme", "sublocality_level_3"),
                component("4", "4", "sublocality_level_4"),
                component("12-7F", "12-7F", "premise")));

        assertThat(policy.evaluate("Tokyo Chuo City 6 Chome-4-12 Building 7F",
                new Candidates(List.of(value))).failureCode()).isNull();
    }

    @Test
    void 우편번호는_양쪽에서_하나씩_확실할때만_충돌을_거절한다() {
        assertThat(policy.evaluate("〒100-0001 " + ADDRESS, new Candidates(List.of(candidate())))
                .failureCode()).isNull();
        assertThat(policy.evaluate("〒100-0002 " + ADDRESS, new Candidates(List.of(candidate())))
                .failureCode()).isEqualTo("ADDRESS_MISMATCH");

        var withoutPostal = candidateWithComponents(candidate().addressComponents().stream()
                .filter(component -> !component.types().contains("postal_code")).toList());
        assertThat(policy.evaluate("〒100-0002 " + ADDRESS, new Candidates(List.of(withoutPostal)))
                .failureCode()).isNull();

        var conflictingProviderPostal = new ArrayList<>(candidate().addressComponents());
        conflictingProviderPostal.add(component("100-0002", "100-0002", "postal_code"));
        assertThat(policy.evaluate("〒100-0001 " + ADDRESS,
                new Candidates(List.of(candidateWithComponents(conflictingProviderPostal)))).failureCode())
                .isEqualTo("ADDRESS_MISMATCH");
    }

    @Test
    void provider의_서로다른_본번component는_불확실하다고_우회하지_않고_거절한다() {
        var components = new ArrayList<>(candidate().addressComponents());
        components.add(component("9-chōme", "9-chōme", "sublocality_level_3"));

        assertThat(policy.evaluate(ADDRESS, new Candidates(List.of(candidateWithComponents(components))))
                .failureCode()).isEqualTo("ADDRESS_MISMATCH");
    }

    @Test
    void 긴_숫자component도_overflow없이_충돌로_거절한다() {
        String longNumber = "9".repeat(100);
        var value = candidateWithComponents(List.of(
                component("日本", "JP", "country"),
                component("東京都", "東京都", "administrative_area_level_1"),
                component(longNumber + "-chōme", longNumber + "-chōme", "sublocality_level_3"),
                component("2", "2", "sublocality_level_4"),
                component("3", "3", "premise")));

        assertThat(policy.evaluate("Tokyo Shiken-ku 1 Chome-2-3", new Candidates(List.of(value)))
                .failureCode()).isEqualTo("ADDRESS_MISMATCH");
    }

    @Test
    void 입력에_서로다른_본번이_두개면_거절하고_같은_본번의_반복은_허용한다() {
        assertThat(policy.evaluate("東京都新宿区西新宿1-2-3 / 4-5-6",
                new Candidates(List.of(candidate()))).failureCode()).isEqualTo("ADDRESS_MISMATCH");
        assertThat(policy.evaluate("東京都新宿区西新宿1-2-3 / 1-2-3",
                new Candidates(List.of(candidate()))).failureCode()).isNull();
    }

    @Test
    void country_누락과_다른국가를_서로_다른_운영사유로_남긴다() {
        var original = candidate();
        var missing = copy(original, original.latitude(), original.longitude(), original.granularity(),
                "", original.administrativeArea(), original.addressComponents());
        var foreign = copy(original, original.latitude(), original.longitude(), original.granularity(),
                "US", original.administrativeArea(), original.addressComponents());

        assertThat(policy.evaluate(ADDRESS, new Candidates(List.of(missing))).failureCode())
                .isEqualTo("COUNTRY_MISSING");
        assertThat(policy.evaluate(ADDRESS, new Candidates(List.of(foreign))).failureCode())
                .isEqualTo("COUNTRY_MISMATCH");
    }

    @Test
    void 고정된_영어응답의_Tokyo도_같은_도쿄지원영역으로_인정한다() {
        var original = candidate();
        var english = copy(original, original.latitude(), original.longitude(), original.granularity(),
                original.countryCode(), "Tokyo", original.addressComponents());

        assertThat(policy.evaluate("Tokyo Shiken-ku 1 Chome-2-3", new Candidates(List.of(english)))
                .failureCode()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = Granularity.class, names = "ROOFTOP", mode = EnumSource.Mode.EXCLUDE)
    void rooftop이_아닌_좌표는_주소나_type과_관계없이_거절한다(Granularity granularity) {
        var original = candidate();
        var value = copy(original, original.latitude(), original.longitude(), granularity,
                original.countryCode(), original.administrativeArea(), original.addressComponents());

        assertThat(policy.evaluate(ADDRESS, new Candidates(List.of(value))).failureCode())
                .isEqualTo("INSUFFICIENT_PRECISION");
    }

    @Test
    void 좌표와_도쿄_지원범위를_반올림전에_검증한다() {
        var original = candidate();
        var outside = copy(original, new BigDecimal("12"), original.longitude(), original.granularity(),
                original.countryCode(), original.administrativeArea(), original.addressComponents());
        var invalid = copy(original, new BigDecimal("90.0000001"), original.longitude(), original.granularity(),
                original.countryCode(), original.administrativeArea(), original.addressComponents());
        var unsupportedArea = copy(original, original.latitude(), original.longitude(), original.granularity(),
                original.countryCode(), "Osaka", original.addressComponents());

        assertThat(policy.evaluate(ADDRESS, new Candidates(List.of(outside))).failureCode())
                .isEqualTo("OUTSIDE_SUPPORTED_AREA");
        assertThat(policy.evaluate(ADDRESS, new Candidates(List.of(invalid))).failureCode())
                .isEqualTo("INVALID_COORDINATES");
        assertThat(policy.evaluate(ADDRESS, new Candidates(List.of(unsupportedArea))).failureCode())
                .isEqualTo("OUTSIDE_SUPPORTED_AREA");
    }

    @Test
    void 여러응답중_안전한후보가_하나면_그후보만_채택하고_둘이면_거절한다() {
        var original = candidate();
        var outside = copy(original, new BigDecimal("12"), original.longitude(), original.granularity(),
                original.countryCode(), original.administrativeArea(), original.addressComponents());

        assertThat(policy.evaluate(ADDRESS, new Candidates(List.of(outside, original))).failureCode()).isNull();
        var distinct = copy(original, new BigDecimal("10.1234568"), original.longitude(), original.granularity(),
                original.countryCode(), original.administrativeArea(), original.addressComponents());
        assertThat(policy.evaluate(ADDRESS, new Candidates(List.of(original, distinct))).failureCode())
                .isEqualTo("AMBIGUOUS_RESULTS");
    }

    @Test
    void 반올림전_숫자좌표가_정확히_같은_중복후보만_하나의_위치로_취급한다() {
        var original = candidate();
        var samePointDifferentScale = copy(original, new BigDecimal("10.123456700"),
                new BigDecimal("20.765432100"), original.granularity(), original.countryCode(),
                original.administrativeArea(), original.addressComponents());
        var roundsToSameButDistinct = copy(original, new BigDecimal("10.12345671"),
                original.longitude(), original.granularity(), original.countryCode(),
                original.administrativeArea(), original.addressComponents());

        assertThat(policy.evaluate(ADDRESS, new Candidates(List.of(original, samePointDifferentScale)))
                .failureCode()).isNull();
        assertThat(policy.evaluate(ADDRESS, new Candidates(List.of(original, roundsToSameButDistinct)))
                .failureCode()).isEqualTo("AMBIGUOUS_RESULTS");
    }

    @Test
    void 여러후보가_모두_탈락하면_임의로_첫후보를_고르지_않는다() {
        var original = candidate();
        var first = copy(original, new BigDecimal("12"), original.longitude(), original.granularity(),
                original.countryCode(), original.administrativeArea(), original.addressComponents());
        var second = copy(original, original.latitude(), original.longitude(), Granularity.APPROXIMATE,
                original.countryCode(), original.administrativeArea(), original.addressComponents());

        assertThat(policy.evaluate(ADDRESS, new Candidates(List.of(first, second))).failureCode())
                .isEqualTo("AMBIGUOUS_RESULTS");
    }

    @Test
    void 기본_비활성과_명시적_보관_수명_지원범위_활성화_조건을_검증한다() {
        var disabled = new LocationJobProperties(false, null, null, null, null, null, null);
        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.maxAttempts()).isEqualTo(8);
        assertThat(new LocationJobProperties(true, null, null, null, null, null, null).isConfigured()).isFalse();
        assertThat(new LocationJobProperties(true, Duration.ofDays(31),
                BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, 4).isConfigured()).isFalse();
        assertThat(properties().isConfigured()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(FailureKind.class)
    void 모든_adapter_실패를_한정된_재시도_정책으로_분류한다(FailureKind kind) {
        var retries = new LocationRetryPolicy();
        assertThat(retries.canRetry(kind)).isEqualTo(List.of(FailureKind.TIMEOUT, FailureKind.CONNECTION_ERROR,
                FailureKind.TRANSIENT_ERROR, FailureKind.QUOTA_EXCEEDED, FailureKind.CAPACITY_EXCEEDED,
                FailureKind.CANCELLED).contains(kind));
    }

    private static GeocodingCandidate candidateWithComponents(List<AddressComponent> components) {
        var original = candidate();
        return copy(original, original.latitude(), original.longitude(), original.granularity(),
                original.countryCode(), original.administrativeArea(), components);
    }

    private static GeocodingCandidate copy(GeocodingCandidate original, BigDecimal latitude, BigDecimal longitude,
                                           Granularity granularity, String countryCode, String administrativeArea,
                                           List<AddressComponent> components) {
        return new GeocodingCandidate(latitude, longitude, granularity, countryCode, administrativeArea,
                components, original.types());
    }
}
