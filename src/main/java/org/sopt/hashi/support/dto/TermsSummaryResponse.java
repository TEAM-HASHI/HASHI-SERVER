package org.sopt.hashi.support.dto;

import java.time.LocalDate;
import org.sopt.hashi.support.TermsSummaryInfo;
import org.sopt.hashi.support.TermsType;

public record TermsSummaryResponse(Long termsId, TermsType type, String title,
        String version, LocalDate effectiveDate) {
    public static TermsSummaryResponse from(TermsSummaryInfo info) {
        return new TermsSummaryResponse(info.termsId(), info.type(), info.title(), info.version(), info.effectiveDate());
    }
}
