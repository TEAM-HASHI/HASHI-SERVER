package org.sopt.hashi.media.dto;

import java.util.List;

public record MediaAssetStatusesResponse(List<MediaAssetStatusResponse> assets) {

    public MediaAssetStatusesResponse {
        assets = List.copyOf(assets);
    }
}
