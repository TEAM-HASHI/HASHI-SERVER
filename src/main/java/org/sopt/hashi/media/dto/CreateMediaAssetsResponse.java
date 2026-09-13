package org.sopt.hashi.media.dto;

import java.util.List;

public record CreateMediaAssetsResponse(List<MediaUploadResponse> uploads) {

    public CreateMediaAssetsResponse {
        uploads = List.copyOf(uploads);
    }
}
