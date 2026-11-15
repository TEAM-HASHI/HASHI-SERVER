package org.sopt.hashi.support;

import java.time.LocalDateTime;

/** 본문과 이미지를 읽지 않는 공지 목록 projection. */
public record NoticeSummaryInfo(Long noticeId, String title, LocalDateTime publishedAt,
        LocalDateTime lastModifiedAt, LocalDateTime createdAt, LocalDateTime updatedAt) {
    public String status() {
        return publishedAt == null ? "DRAFT" : "PUBLISHED";
    }
}
