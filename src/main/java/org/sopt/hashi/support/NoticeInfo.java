package org.sopt.hashi.support;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.sopt.hashi.media.MediaImage;

/** 공개된 포트 결과. entity를 노출하지 않는다. */
public record NoticeInfo(Long noticeId, String title, List<NoticeBlock> body,
        String status, LocalDateTime publishedAt, LocalDateTime lastModifiedAt,
        List<Attachment> images, LocalDateTime createdAt, LocalDateTime updatedAt) {
    public record Attachment(UUID assetId, MediaImage image) {}
}
