package org.sopt.hashi.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import org.sopt.hashi.support.TermsClause;
import org.sopt.hashi.support.TermsCommand;
import org.sopt.hashi.support.TermsType;

public record SaveTermsRequest(
        @NotNull TermsType type,
        @NotBlank @Size(max = 100) String title,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,49}") String version,
        @NotNull LocalDate effectiveDate,
        @NotEmpty @Size(max = 200) List<@NotNull TermsClause> clauses) {
    public TermsCommand toCommand() { return new TermsCommand(type, title, version, effectiveDate, clauses); }
}
