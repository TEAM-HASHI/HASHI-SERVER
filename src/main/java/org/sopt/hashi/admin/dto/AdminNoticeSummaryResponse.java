package org.sopt.hashi.admin.dto;

import java.time.LocalDateTime;
import org.sopt.hashi.support.NoticeSummaryInfo;

public record AdminNoticeSummaryResponse(Long noticeId, String title, String status,
        LocalDateTime publishedAt, LocalDateTime lastModifiedAt,
        LocalDateTime createdAt, LocalDateTime updatedAt) {
    public static AdminNoticeSummaryResponse from(NoticeSummaryInfo info) {
        return new AdminNoticeSummaryResponse(info.noticeId(), info.title(), info.status(),
                info.publishedAt(), info.lastModifiedAt(), info.createdAt(), info.updatedAt());
    }
}
