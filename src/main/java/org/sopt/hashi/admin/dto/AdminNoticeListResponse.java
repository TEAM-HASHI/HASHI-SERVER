package org.sopt.hashi.admin.dto;

import java.util.List;
import org.sopt.hashi.support.NoticeSummaryInfo;
import org.springframework.data.domain.Page;

public record AdminNoticeListResponse(List<AdminNoticeSummaryResponse> notices,
        int page, int size, long totalCount, int totalPages) {
    public static AdminNoticeListResponse from(Page<NoticeSummaryInfo> notices) {
        return new AdminNoticeListResponse(notices.getContent().stream().map(AdminNoticeSummaryResponse::from).toList(),
                notices.getNumber(), notices.getSize(), notices.getTotalElements(), notices.getTotalPages());
    }
}
