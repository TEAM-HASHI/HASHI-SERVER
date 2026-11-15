package org.sopt.hashi.support;

import java.time.LocalDate;

public record TermsSummaryInfo(Long termsId, TermsType type, String title,
        String version, LocalDate effectiveDate) {}
