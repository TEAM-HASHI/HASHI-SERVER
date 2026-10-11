package org.sopt.hashi.admin.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;

/** null을 명시하면 소속을 해제한다. 필드 누락은 의도하지 않은 해제를 막기 위해 거절한다. */
public record SetRestaurantMapRegionRequest(
        @Schema(description = "직접 배정할 관광 지역 ID. null을 명시하면 소속만 해제하며 주소와 좌표는 유지",
                example = "12", nullable = true)
        @JsonProperty(required = true) @Positive Long mapRegionId) {
}
