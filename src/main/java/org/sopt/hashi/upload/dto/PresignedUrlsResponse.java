package org.sopt.hashi.upload.dto;

import java.util.List;

public record PresignedUrlsResponse(
        List<PresignedUrlResponse> uploads
) {

    public PresignedUrlsResponse {
        uploads = List.copyOf(uploads);
    }
}
