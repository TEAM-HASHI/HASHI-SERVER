package org.sopt.hashi.admin.dto;

import java.util.List;
import org.sopt.hashi.support.TermsInfo;
import org.springframework.data.domain.Page;

public record AdminTermsListResponse(List<AdminTermsResponse> terms,
        int page, int size, long totalCount, int totalPages) {
    public static AdminTermsListResponse from(Page<TermsInfo> terms) {
        return new AdminTermsListResponse(terms.getContent().stream().map(AdminTermsResponse::from).toList(),
                terms.getNumber(), terms.getSize(), terms.getTotalElements(), terms.getTotalPages());
    }
}
