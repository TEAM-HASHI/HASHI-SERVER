package org.sopt.hashi.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.sopt.hashi.support.NoticeBlock;
import org.sopt.hashi.support.NoticeCommand;

/** 전체 저장 입력. 상세 서식·중복 이미지 검증은 support가 소유한다. */
public record SaveNoticeRequest(
        @NotBlank(message = "제목은 필수입니다")
        @Size(max = 100, message = "제목은 100자 이하여야 합니다") String title,
        @NotEmpty(message = "본문은 필수입니다") List<NoticeBlock> body,
        @NotNull(message = "이미지 목록은 필수입니다")
        @Size(max = 10, message = "이미지는 10개 이하여야 합니다")
        List<@NotNull(message = "이미지 ID는 비어 있을 수 없습니다") UUID> imageAssetIds) {
    public NoticeCommand toCommand() { return new NoticeCommand(title, body, imageAssetIds); }
}
