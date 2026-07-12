package org.sopt.hashi.upload.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** presigned URL 발급 요청. */
public record IssuePresignedUrlRequest(
        @Schema(description = "업로드 용도: profile / review / restaurant / restaurant-menu / magazine",
                example = "profile")
        @NotBlank(message = "usage는 필수입니다.")
        String usage,

        @Schema(description = "파일 Content-Type", example = "image/jpeg")
        @NotBlank(message = "contentType은 필수입니다.")
        String contentType,

        @Schema(description = "파일 크기(byte, 최대 5MB)", example = "204800")
        @NotNull(message = "fileSize는 필수입니다.")
        @Positive(message = "fileSize는 1 이상이어야 합니다.")
        Long fileSize
) {
}
