package org.sopt.hashi.admin.dto;

import java.util.List;
import org.sopt.hashi.user.AdminUserInfo;
import org.springframework.data.domain.Page;

/** 어드민 회원 목록 응답 — offset 페이지네이션 메타(page·size·totalCount·totalPages) 포함. */
public record AdminUserListResponse(
        List<AdminUserResponse> users,
        int page,
        int size,
        long totalCount,
        int totalPages) {

    public static AdminUserListResponse from(
            Page<AdminUserInfo> page,
            List<AdminUserResponse> users
    ) {
        return new AdminUserListResponse(
                List.copyOf(users),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
