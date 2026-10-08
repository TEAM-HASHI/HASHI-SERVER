package org.sopt.hashi.admin.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.sopt.hashi.media.MediaImage;
import org.sopt.hashi.support.NoticeBlock;
import org.sopt.hashi.support.NoticeInfo;

public record AdminNoticeResponse(Long noticeId, String title, List<NoticeBlock> body,
        String status, LocalDateTime publishedAt, LocalDateTime lastModifiedAt,
        List<Attachment> images, LocalDateTime createdAt, LocalDateTime updatedAt) {
    public static AdminNoticeResponse from(NoticeInfo info) {
        return new AdminNoticeResponse(info.noticeId(), info.title(), info.body(), info.status(),
                info.publishedAt(), info.lastModifiedAt(), info.images().stream()
                        .map(image -> new Attachment(image.assetId(), image.image())).toList(),
                info.createdAt(), info.updatedAt());
    }

    public record Attachment(UUID assetId, MediaImage image) {}
}
