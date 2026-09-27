package org.sopt.hashi.media.dto;

import java.util.UUID;
import org.sopt.hashi.media.domain.ImageProcessingStatus;

public record MediaAssetStatusResponse(
        UUID assetId,
        ImageProcessingStatus status
) {
}
