package org.sopt.hashi.user.recent;

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
                        @Schema(description = "실제 기기 조회 시각. 기록이 없는 이전 버전은 생략하고 최신순으로 전송합니다.")
                        Instant viewedAt) {}
}
