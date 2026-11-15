package org.sopt.hashi.support;

import java.time.LocalDate;
import java.util.List;

public record TermsCommand(TermsType type, String title, String version,
        LocalDate effectiveDate, List<TermsClause> clauses) {}
