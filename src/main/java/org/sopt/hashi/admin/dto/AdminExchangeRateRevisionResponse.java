package org.sopt.hashi.admin.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.sopt.hashi.exchangerate.ExchangeRatePort;

public record AdminExchangeRateRevisionResponse(
        long id, QuoteResponse quote, String source, Long adminId, LocalDateTime createdAtUtc) {

    public record QuoteResponse(LocalDate appliedDate, LocalDate quotedDate, BigDecimal krwPer100Jpy) {}

    public static AdminExchangeRateRevisionResponse from(ExchangeRatePort.Revision revision) {
        var quote = revision.quote();
        return new AdminExchangeRateRevisionResponse(revision.id(),
                new QuoteResponse(quote.appliedDate(), quote.quotedDate(), quote.krwPer100Jpy()),
                revision.source(), revision.adminId(), revision.createdAtUtc());
    }
}
