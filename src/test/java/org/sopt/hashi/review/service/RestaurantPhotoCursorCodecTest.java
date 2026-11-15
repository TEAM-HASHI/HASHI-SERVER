package org.sopt.hashi.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.review.domain.RestaurantPhotoFilter;
import org.sopt.hashi.review.domain.ReviewPhotoRepository;
import org.sopt.hashi.shared.error.BusinessException;

class RestaurantPhotoCursorCodecTest {
    @Test
    void 출처별_정렬키와_혼합단계를_그대로_복원한다() {
        var position = new RestaurantPhotoCursorCodec.Cursor(7, 13, 45,
                new ReviewPhotoRepository.Position(LocalDateTime.parse("2026-10-20T01:02:03.123456"), 31, 2, 32),
                3, false);
        String token = RestaurantPhotoCursorCodec.encode(position, 8, RestaurantPhotoFilter.ALL);
        assertThat(RestaurantPhotoCursorCodec.decode(token, 8, RestaurantPhotoFilter.ALL)).isEqualTo(position);
        assertThat(RestaurantPhotoCursorCodec.decode(null, 8, RestaurantPhotoFilter.ALL))
                .isEqualTo(RestaurantPhotoCursorCodec.Cursor.first());
    }

    @Test
    void 다른_식당이나_필터에_커서를_재사용할수없다() {
        String token = RestaurantPhotoCursorCodec.encode(RestaurantPhotoCursorCodec.Cursor.first(),
                8, RestaurantPhotoFilter.ALL);
        assertThatThrownBy(() -> RestaurantPhotoCursorCodec.decode(token, 9, RestaurantPhotoFilter.ALL))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> RestaurantPhotoCursorCodec.decode(token, 8, RestaurantPhotoFilter.REVIEW))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 깨진_형식과_음수_범위초과_불완전한_정렬키는_거절한다() {
        for (String raw : List.of("1|8|ALL|-1|1|0|||||0|0", "1|8|ALL|1|0|0|||||0|0",
                "1|8|ALL|0|0|0|||||4|0", "1|8|ALL|0|0|0||1|||0|0",
                "1|8|ALL|0|0|0|||||0|true", "1|8|ALL|0|0|0|bad|1|0|2|0|0")) {
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
            assertThatThrownBy(() -> RestaurantPhotoCursorCodec.decode(token, 8, RestaurantPhotoFilter.ALL))
                    .isInstanceOf(BusinessException.class);
        }
        for (String token : List.of("", " ", "%%%", "a".repeat(513))) {
            assertThatThrownBy(() -> RestaurantPhotoCursorCodec.decode(token, 8, RestaurantPhotoFilter.ALL))
                    .isInstanceOf(BusinessException.class);
        }
    }
}
