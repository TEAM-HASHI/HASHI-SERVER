package org.sopt.hashi.upload.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record IssuePresignedUrlRequest(
        @NotBlank(message = "usage는 필수입니다.")
        String usage,

        @NotBlank(message = "contentType은 필수입니다.")
        String contentType,

        @NotNull(message = "fileSize는 필수입니다.")
        @Positive(message = "fileSize는 1 이상이어야 합니다.")
        Long fileSize
) {
}
