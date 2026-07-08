package org.sopt.hashi.upload.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record PresignedUrlRequest(
        @NotBlank(message = "usage는 필수입니다.")
        String usage,

        @NotBlank(message = "fileName은 필수입니다.")
        @Size(max = 255, message = "fileName은 최대 255자까지 입력할 수 있습니다.")
        String fileName,

        @NotBlank(message = "contentType은 필수입니다.")
        String contentType,

        @NotNull(message = "fileSize는 필수입니다.")
        @Positive(message = "fileSize는 1 이상이어야 합니다.")
        Long fileSize
) {
}
