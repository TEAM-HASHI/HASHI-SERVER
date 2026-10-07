package org.sopt.hashi.restaurant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        return new GeocodingCandidate(new BigDecimal("10.1234567"), new BigDecimal("20.7654321"),
                Granularity.ROOFTOP, "JP", "東京都", List.of(
                component("日本", "JP", "country"), component("東京都", "東京都", "administrative_area_level_1"),
                component("試験区", "試験区", "locality"), component("架空町", "架空町", "sublocality_level_1"),
                component("1丁目", "1丁目", "sublocality_level_2"), component("2番", "2", "sublocality_level_3"),
                component("3号", "3", "sublocality_level_4")), List.of("street_address"));
    }

    static GeocodingCandidate numericPremiseCandidate(String premise) {
        var original = candidate();
        return new GeocodingCandidate(original.latitude(), original.longitude(), Granularity.ROOFTOP,
                "JP", "東京都", List.of(component(premise, premise, "premise"),
                component("2", "2", "sublocality_level_4"), component("1丁目", "1丁目", "sublocality_level_3"),
                component("架空町", "架空町", "sublocality_level_2"), component("試験区", "試験区", "locality"),
                component("東京都", "東京都", "administrative_area_level_1"), component("日本", "JP", "country")),
                List.of("premise"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"3", "３"})
    void 숫자만_있는_premise는_street_number가_없을때_마지막_번지로_비교한다(String premise) {
        assertThat(policy.evaluate(ADDRESS, new Candidates(List.of(numericPremiseCandidate(premise))))
                .failureCode()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "3号", "3A", "三", "③", "３別館", "3-4", "03", "4", "13"})
    void 건물명과_불명확하거나_다른_premise는_채택하지_않는다(String premise) {
        assertThat(policy.evaluate(ADDRESS, new Candidates(List.of(numericPremiseCandidate(premise))))
                .failureCode()).isEqualTo("ADDRESS_MISMATCH");
    }

    @ParameterizedTest
    @ValueSource(strings = {"street_number", "premise", "unknown_component"})
    void premise의_상충_중복_알수없는_구성요소는_거절한다(String extraType) {
        var original = numericPremiseCandidate("3");
        var components = new ArrayList<>(original.addressComponents());
        components.add(component("3", "3", extraType));
        var value = new GeocodingCandidate(original.latitude(), original.longitude(), original.granularity(),
                original.countryCode(), original.administrativeArea(), components, original.types());
        assertThat(policy.evaluate(ADDRESS, new Candidates(List.of(value))).failureCode())
                .isEqualTo("ADDRESS_MISMATCH");
    }

    @ParameterizedTest
    @ValueSource(strings = {"country", "postal_code", "sublocality"})
    void premise가_국가나_우편번호_등_다른_역할을_겸하면_거절한다(String extraType) {
        var original = numericPremiseCandidate("3");
        var components = new ArrayList<>(original.addressComponents());
        components.removeIf(component -> component.types().contains("premise")
                || component.types().contains(extraType));
        components.add(new AddressComponent("3", "JP", List.of("premise", extraType)));
        var value = new GeocodingCandidate(original.latitude(), original.longitude(), original.granularity(),
                original.countryCode(), original.administrativeArea(), components, original.types());
        var decision = policy.evaluate(ADDRESS, new Candidates(List.of(value)));
        assertThat(decision.failureCode()).isEqualTo("ADDRESS_MISMATCH");
        assertThat(decision.coordinates()).isNull();
    }

    @Test
    void premise의_political_분류는_주소의_다른_역할로_사용하지_않는다() {
        var original = numericPremiseCandidate("3");
        var components = new ArrayList<>(original.addressComponents());
        components.set(0, new AddressComponent("3", "3", List.of("premise", "political")));
        var value = new GeocodingCandidate(original.latitude(), original.longitude(), original.granularity(),
                original.countryCode(), original.administrativeArea(), components, original.types());
        assertThat(policy.evaluate(ADDRESS, new Candidates(List.of(value))).failureCode()).isNull();
    }

    @Test
    void 같은_premise를_두개의_번지_구간으로_중복_사용하지_않는다() {
        var original = numericPremiseCandidate("3");
        var components = new ArrayList<>(original.addressComponents());
        components.removeIf(component -> component.types().contains("premise")
                || component.types().contains("sublocality_level_4"));
        components.add(new AddressComponent("3", "3", List.of("premise", "sublocality_level_4")));
        var value = new GeocodingCandidate(original.latitude(), original.longitude(), original.granularity(),
                original.countryCode(), original.administrativeArea(), components, original.types());
        assertThat(policy.evaluate("東京都試験区架空町1-3-3", new Candidates(List.of(value))).failureCode())
                .isEqualTo("ADDRESS_MISMATCH");
    }

    @ParameterizedTest
    @ValueSource(strings = {"locality", "administrative_area_level_1", "country", "sublocality_level_3",
            "sublocality_level_4"})
    void premise가_있어도_행정구역과_세구간_번지는_생략하지_않는다(String missingType) {
        var original = numericPremiseCandidate("3");
        var components = original.addressComponents().stream()
                .filter(component -> !component.types().contains(missingType)).toList();
        var value = new GeocodingCandidate(original.latitude(), original.longitude(), original.granularity(),
                original.countryCode(), original.administrativeArea(), components, original.types());
        assertThat(policy.evaluate(ADDRESS, new Candidates(List.of(value))).failureCode())
                .isEqualTo("ADDRESS_MISMATCH");
    }

    static AddressComponent component(String value, String shortValue, String type) {
        return new AddressComponent(value, shortValue, List.of(type));
    }

    @ParameterizedTest
    @ValueSource(strings = {ADDRESS, "東京都 試験区 架空町１丁目２番３号", "東京都試験区架空町1-2-3",
            "日本 東京都試験区架空町1‐2‐3"})
    void 완전한_주소만_명시한_정규화와_소수점6자리_반올림으로_자동_채택한다(String address) {
        var decision = policy.evaluate(address, new Candidates(List.of(candidate())));
        assertThat(decision.failureCode()).isNull();
        assertThat(decision.coordinates().getLatitude()).isEqualByComparingTo("10.123457");
        assertThat(decision.coordinates().getLongitude()).isEqualByComparingTo("20.765432");
    }

    @ParameterizedTest
    @ValueSource(strings = {"東京都試験区架空町1-2-4", "東京都試験区架空町", "東京都試験区別町1-2-3",
            "Tokyo Shiken-ku Kaku-cho 1-2-3", "도쿄도 시험구 가공정 1-2-3", "東京都試験区架空町1-2-3別館",
            "東京都試験区架空町11-2-3", "東京都試験区架空町1-12-3"})
    void 다른_번지와_불완전한_주소와_언어_추측은_채택하지_않는다(String address) {
        assertThat(policy.evaluate(address, new Candidates(List.of(candidate()))).failureCode())
                .isEqualTo("ADDRESS_MISMATCH");
    }

    @Test
    void 복수_후보의_첫번째가_일치해도_자동_선택하지_않는다() {
        assertThat(policy.evaluate(ADDRESS, new Candidates(List.of(candidate(), candidate()))).failureCode())
                .isEqualTo("AMBIGUOUS_RESULTS");
    }

    @ParameterizedTest
    @EnumSource(value = Granularity.class, names = "ROOFTOP", mode = EnumSource.Mode.EXCLUDE)
    void 넓은_지역_중심과_보간_좌표는_주소가_같아도_채택하지_않는다(Granularity accuracy) {
        var original = candidate();
        var value = new GeocodingCandidate(original.latitude(), original.longitude(), accuracy, "JP", "東京都",
                original.addressComponents(), original.types());
        assertThat(policy.evaluate(ADDRESS, new Candidates(List.of(value))).failureCode())
                .isEqualTo("INSUFFICIENT_PRECISION");
    }

    @Test
    void 국가와_지원_영역과_원본_좌표_범위를_반올림_전에_검증한다() {
        var original = candidate();
        var foreign = new GeocodingCandidate(original.latitude(), original.longitude(), Granularity.ROOFTOP,
                "US", "東京都", original.addressComponents(), original.types());
        var outside = new GeocodingCandidate(new BigDecimal("12"), original.longitude(), Granularity.ROOFTOP,
                "JP", "東京都", original.addressComponents(), original.types());
        var invalid = new GeocodingCandidate(new BigDecimal("90.0000001"), original.longitude(), Granularity.ROOFTOP,
                "JP", "東京都", original.addressComponents(), original.types());
        assertThat(policy.evaluate(ADDRESS, new Candidates(List.of(foreign))).failureCode()).isEqualTo("COUNTRY_MISMATCH");
        assertThat(policy.evaluate(ADDRESS, new Candidates(List.of(outside))).failureCode()).isEqualTo("OUTSIDE_SUPPORTED_AREA");
        assertThat(policy.evaluate(ADDRESS, new Candidates(List.of(invalid))).failureCode()).isEqualTo("INVALID_COORDINATES");
    }

    @Test
    void 상충하거나_누락된_구성요소는_rooftop으로_보완하지_않는다() {
        var original = candidate();
        var components = new ArrayList<>(original.addressComponents());
        components.add(component("別区", "別区", "locality"));
        var duplicate = new GeocodingCandidate(original.latitude(), original.longitude(), Granularity.ROOFTOP,
                "JP", "東京都", components, original.types());
        var missing = new GeocodingCandidate(original.latitude(), original.longitude(), Granularity.ROOFTOP,
                "JP", "東京都", List.of(), original.types());
        assertThat(policy.evaluate(ADDRESS, new Candidates(List.of(duplicate))).coordinates()).isNull();
        assertThat(policy.evaluate(ADDRESS, new Candidates(List.of(missing))).coordinates()).isNull();
    }

    @Test
    void street_number와_일본어_행정구역_경로도_완전한_주소이면_채택한다() {
        var original = candidate();
        var value = new GeocodingCandidate(original.latitude(), original.longitude(), Granularity.ROOFTOP,
                "JP", "東京都", List.of(component("日本", "JP", "country"),
                component("東京都", "東京都", "administrative_area_level_1"),
                component("試験区", "試験区", "locality"), component("架空町", "架空町", "route"),
                component("1-2-3", "1-2-3", "street_number")), List.of("premise"));
        assertThat(policy.evaluate(ADDRESS, new Candidates(List.of(value))).failureCode()).isNull();
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
}
