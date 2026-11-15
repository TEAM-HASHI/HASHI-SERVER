package org.sopt.hashi.support.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.sopt.hashi.media.MediaImage;
import org.sopt.hashi.support.NoticeBlock;
import org.sopt.hashi.support.NoticeInfo;

public record NoticeResponse(Long noticeId, String title, List<NoticeBlock> body,
        String status, LocalDateTime publishedAt, LocalDateTime lastModifiedAt,
        List<Attachment> images) {
    public static NoticeResponse from(NoticeInfo info) {
        return new NoticeResponse(info.noticeId(), info.title(), info.body(), info.status(),
                info.publishedAt(), info.lastModifiedAt(), info.images().stream()
                        .map(image -> new Attachment(image.assetId(), image.image())).toList());
    }

    public record Attachment(UUID assetId, MediaImage image) {}
}
