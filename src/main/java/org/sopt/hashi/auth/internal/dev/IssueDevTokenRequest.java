package org.sopt.hashi.auth.internal.dev;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 개발용 토큰 발급 요청. subjectId는 role에 따라 userId(USER)·adminId(ADMIN)·kakaoId(ONBOARDING)로
 * 해석되며, 생략하면 1이다. 도메인 API를 실데이터로 시험하려면 DB에 존재하는 식별자를 넣는다.
 */
public record IssueDevTokenRequest(
        @Schema(description = "발급할 역할", example = "USER")
        @NotNull(message = "role은 필수입니다(USER/ADMIN/ONBOARDING)") DevTokenRole role,
        @Schema(description = "대상 식별자(선택, 기본 1) — role에 따라 userId/adminId/kakaoId", example = "1")
        @Positive(message = "subjectId는 양수여야 합니다") Long subjectId) {

    private static final long DEFAULT_SUBJECT_ID = 1L;

    public Long resolvedSubjectId() {
        return (subjectId == null) ? DEFAULT_SUBJECT_ID : subjectId;
    }
}
