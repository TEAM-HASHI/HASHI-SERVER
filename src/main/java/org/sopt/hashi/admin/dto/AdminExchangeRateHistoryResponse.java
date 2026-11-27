package org.sopt.hashi.admin.dto;

import java.util.List;
import org.sopt.hashi.exchangerate.ExchangeRatePort;
import org.springframework.data.domain.Page;

public record AdminExchangeRateHistoryResponse(
        List<AdminExchangeRateRevisionResponse> revisions, int page, int size, long totalCount, int totalPages) {

    public static AdminExchangeRateHistoryResponse from(Page<ExchangeRatePort.Revision> page) {
        return new AdminExchangeRateHistoryResponse(page.map(AdminExchangeRateRevisionResponse::from).getContent(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
