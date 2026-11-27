package org.sopt.hashi.user.recent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public record RecentRestaurantRequest(
        @NotNull @Size(max = 50) List<@NotNull @Valid Visit> visits) {
    public record Visit(@NotNull @Positive Long restaurantId,
                        @Schema(description = "기기 조회 시각. 시각이 없는 기록은 최신순으로 전송합니다. "
                                + "1970년 이전이거나 서버보다 5분 넘게 빠른 시각은 시각이 없는 기록으로 처리합니다.")
                        Instant viewedAt) {}
}
