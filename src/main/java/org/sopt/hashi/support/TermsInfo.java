package org.sopt.hashi.support;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record TermsInfo(Long termsId, TermsType type, String title, String version,
        LocalDate effectiveDate, List<TermsClause> clauses, String status, LocalDateTime publishedAt) {}
