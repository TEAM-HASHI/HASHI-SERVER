package org.sopt.hashi.upload.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/** presigned URL 벌크 발급 요청. 한 요청의 파일은 모두 같은 업로드 용도를 사용한다. */
public record IssuePresignedUrlsRequest(
        @Schema(description = "업로드 용도: profile / review / restaurant / restaurant-menu / magazine",
                example = "review")
        @NotBlank(message = "usage는 필수입니다.")
        String usage,

        @Schema(description = "업로드할 파일 목록(1~10개)")
        @NotEmpty(message = "files는 최소 1개 이상이어야 합니다.")
        @Size(max = 10, message = "files는 최대 10개까지 요청할 수 있습니다.")
        List<@NotNull(message = "파일 정보는 null일 수 없습니다.") @Valid FileRequest> files
) {

    public record FileRequest(
            @Schema(description = "파일 Content-Type", example = "image/jpeg")
            @NotBlank(message = "contentType은 필수입니다.")
            String contentType,

            @Schema(description = "파일 크기(byte, 파일당 최대 5MB)", example = "1048576")
            @NotNull(message = "fileSize는 필수입니다.")
            @Positive(message = "fileSize는 1 이상이어야 합니다.")
            Long fileSize
    ) {
    }
}
