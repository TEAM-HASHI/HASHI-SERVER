package org.sopt.hashi.restaurant.internal.map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sopt.hashi.restaurant.code.RestaurantErrorCode;
import org.sopt.hashi.restaurant.domain.RestaurantMapSort;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;

class MapCursorCodecTest {
    private final MapSessionProperties properties = configured();
    private final MapCursorCodec codec = new MapCursorCodec(properties);

    @Test
    void 고정_서명키로_다른_인스턴스에서도_같은_불투명_토큰을_읽는다() {
        var id = new MapSessionId(127, UUID.randomUUID());
        String token = codec.encode(id, RestaurantMapSort.REVIEWS, 499);
        assertThat(token).hasSize(74).doesNotContain(id.value());
        assertThat(new MapCursorCodec(configured()).decode(token))
                .isEqualTo(new MapCursorCodec.DecodedCursor(id, RestaurantMapSort.REVIEWS, 499));
        assertThat(codec.encode(id, RestaurantMapSort.REVIEWS, 499)).isEqualTo(token);
    }

    @Test
    void 변조_과길이_비정규_base64_일반목록cursor를_거절하고_원문을_남기지_않는다() {
        String token = codec.encode(new MapSessionId(0, UUID.randomUUID()), RestaurantMapSort.RECOMMEND, 10);
        for (String value : new String[]{"!" + token.substring(1), token + "=", "x".repeat(513), "eyJpZCI6MX0"}) {
            assertThatThrownBy(() -> codec.decode(value)).isInstanceOfSatisfying(BusinessException.class, exception -> {
                assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT);
                assertThat(exception.getMessage()).doesNotContain(value);
                assertThat(exception.getCause()).isNull();
            });
        }
        byte[] bytes = Base64.getUrlDecoder().decode(token);
        bytes[8] ^= 1;
        assertThatThrownBy(() -> codec.decode(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)))
                .isInstanceOf(BusinessException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 18, 19})
    void 서명이_맞아도_버전_슬롯_정렬_후보위치가_잘못되면_거절한다(int offset) throws Exception {
        byte[] bytes = Base64.getUrlDecoder().decode(codec.encode(new MapSessionId(0, UUID.randomUUID()),
                RestaurantMapSort.RATING, 10));
        if (offset == 19) {
            ByteBuffer.wrap(bytes).putInt(19, 501);
        } else {
            bytes[offset] = (byte) 255;
        }
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(properties.requireSigningKey());
        System.arraycopy(mac.doFinal(Arrays.copyOf(bytes, 23)), 0, bytes, 23, 32);
        assertThatThrownBy(() -> codec.decode(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT));
    }

    @Test
    void 설정_누락과_잘못된_키는_요청시_503이며_설정_toString에_키를_넣지_않는다() {
        MapSessionProperties missing = new MapSessionProperties();
        for (String key : new String[]{null, "", "private-fixture", "x".repeat(129)}) {
            missing.setSigningKey(key);
            assertThatThrownBy(missing::requireSigningKey).isInstanceOfSatisfying(BusinessException.class,
                    exception -> assertThat(exception.getErrorCode()).isEqualTo(RestaurantErrorCode.MAP_SESSION_UNAVAILABLE));
        }
        assertThat(configured().toString()).doesNotContain("fixture", "signingKey");
    }

    static MapSessionProperties configured() {
        var properties = new MapSessionProperties();
        properties.setSigningKey(Base64.getEncoder().encodeToString(
                "synthetic-map-test-key-32-bytes-only".getBytes(StandardCharsets.UTF_8)));
        return properties;
    }
}
