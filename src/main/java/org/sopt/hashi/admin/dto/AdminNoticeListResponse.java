package org.sopt.hashi.admin.dto;

import java.util.List;
import org.sopt.hashi.support.NoticeInfo;
import org.springframework.data.domain.Page;

public record AdminNoticeListResponse(List<AdminNoticeResponse> notices,
        int page, int size, long totalCount, int totalPages) {
    public static AdminNoticeListResponse from(Page<NoticeInfo> notices) {
        return new AdminNoticeListResponse(notices.getContent().stream().map(AdminNoticeResponse::from).toList(),
                notices.getNumber(), notices.getSize(), notices.getTotalElements(), notices.getTotalPages());
    }
}
