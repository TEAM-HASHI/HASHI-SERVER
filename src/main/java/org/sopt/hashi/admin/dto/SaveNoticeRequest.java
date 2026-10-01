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
        @NotBlank @Size(max = 100) String title,
        @NotEmpty List<NoticeBlock> body,
        @NotNull @Size(max = 10) List<@NotNull UUID> imageAssetIds) {
    public NoticeCommand toCommand() { return new NoticeCommand(title, body, imageAssetIds); }
}
