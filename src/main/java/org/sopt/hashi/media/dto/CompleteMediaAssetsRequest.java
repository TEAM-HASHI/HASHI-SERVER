package org.sopt.hashi.media.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record CompleteMediaAssetsRequest(
        @NotEmpty(message = "assetIds는 최소 1개 이상이어야 합니다.")
        @Size(max = 10, message = "assetIds는 최대 10개까지 요청할 수 있습니다.")
        List<@NotNull(message = "assetId는 null일 수 없습니다.") UUID> assetIds
) {
}
