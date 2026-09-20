package org.sopt.hashi.media.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.sopt.hashi.media.domain.MediaPurpose;

public record CreateMediaAssetsRequest(
        @Schema(description = "이미지 업로드 목적", example = "REVIEW")
        @NotNull(message = "purpose는 필수입니다.")
        MediaPurpose purpose,

        @Schema(description = "업로드할 이미지 목록(1~10개)")
        @NotEmpty(message = "files는 최소 1개 이상이어야 합니다.")
        @Size(max = 10, message = "files는 최대 10개까지 요청할 수 있습니다.")
        List<@NotNull(message = "파일 정보는 null일 수 없습니다.") @Valid FileRequest> files
) {

    public record FileRequest(
            @Schema(description = "원본 파일 Content-Type", example = "image/jpeg")
            @NotBlank(message = "contentType은 필수입니다.")
            String contentType,

            @Schema(description = "원본 파일 크기(byte, 최대 5MB)", example = "1048576")
            @NotNull(message = "fileSize는 필수입니다.")
            @Positive(message = "fileSize는 1 이상이어야 합니다.")
            Long fileSize
    ) {
    }
}
