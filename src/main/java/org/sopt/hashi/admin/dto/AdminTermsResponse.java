package org.sopt.hashi.admin.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.sopt.hashi.support.TermsClause;
import org.sopt.hashi.support.TermsInfo;
import org.sopt.hashi.support.TermsType;

public record AdminTermsResponse(Long termsId, TermsType type, String title, String version,
        LocalDate effectiveDate, List<TermsClause> clauses, String status, LocalDateTime publishedAt) {
    public static AdminTermsResponse from(TermsInfo info) {
        return new AdminTermsResponse(info.termsId(), info.type(), info.title(), info.version(),
                info.effectiveDate(), info.clauses(), info.status(), info.publishedAt());
    }
}
