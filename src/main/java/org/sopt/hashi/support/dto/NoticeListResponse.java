package org.sopt.hashi.support.dto;

import java.time.LocalDateTime;
import java.util.List;

public record NoticeListResponse(List<Item> notices, String nextCursor, boolean hasNext) {
    public record Item(Long noticeId, String title, LocalDateTime lastModifiedAt) {}
}
