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
        @NotNull(message = "약관 유형은 필수입니다") TermsType type,
        @NotBlank(message = "약관 제목은 필수입니다")
        @Size(max = 100, message = "약관 제목은 100자 이내입니다") String title,
        @NotBlank(message = "약관 버전은 필수입니다")
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,49}",
                message = "약관 버전은 영문 또는 숫자로 시작하고 영문, 숫자, 점, 밑줄, 하이픈으로 50자 이내여야 합니다") String version,
        @NotNull(message = "약관 시행일은 필수입니다") LocalDate effectiveDate,
        @NotEmpty(message = "약관 조항은 최소 1개 이상 필요합니다")
        @Size(max = 200, message = "약관 조항은 최대 200개까지 등록할 수 있습니다")
        List<@NotNull(message = "약관 조항은 null일 수 없습니다") TermsClause> clauses) {
    public TermsCommand toCommand() { return new TermsCommand(type, title, version, effectiveDate, clauses); }
}
