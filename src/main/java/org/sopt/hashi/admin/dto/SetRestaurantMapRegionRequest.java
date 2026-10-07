package org.sopt.hashi.admin.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Positive;

/** null을 명시하면 소속을 해제한다. 필드 누락은 의도하지 않은 해제를 막기 위해 거절한다. */
public record SetRestaurantMapRegionRequest(@JsonProperty(required = true) @Positive Long mapRegionId) {
}
