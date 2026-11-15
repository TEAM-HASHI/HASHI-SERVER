package org.sopt.hashi.support.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.sopt.hashi.support.TermsClause;
import org.sopt.hashi.support.TermsInfo;
import org.sopt.hashi.support.TermsType;

public record TermsResponse(Long termsId, TermsType type, String title, String version,
        LocalDate effectiveDate, List<TermsClause> clauses, String status, LocalDateTime publishedAt) {
    public static TermsResponse from(TermsInfo info) {
        return new TermsResponse(info.termsId(), info.type(), info.title(), info.version(),
                info.effectiveDate(), info.clauses(), info.status(), info.publishedAt());
    }
}
