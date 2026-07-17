package org.sopt.hashi.admin.service;

import org.sopt.hashi.admin.dto.AdminUserListResponse;
import org.sopt.hashi.user.AdminUserSortType;
import org.sopt.hashi.user.UserPort;
import org.springframework.stereotype.Service;

/**
 * 어드민 회원 관리 — 진입점 모듈이라 도메인 로직 없이 {@link UserPort}로 위임하고
 * 응답 DTO 변환만 한다(architecture.md §9).
 */
@Service
public class AdminUserService {

    private final UserPort userPort;

    public AdminUserService(UserPort userPort) {
        this.userPort = userPort;
    }

    /** 회원 목록 — offset 페이지네이션. 정렬은 sortType, keyword가 있으면 닉네임 부분 일치 검색. */
    public AdminUserListResponse getUsers(AdminUserSortType sortType, String keyword, int page, int size) {
        return AdminUserListResponse.from(userPort.findPageByAdmin(sortType, keyword, page, size));
    }
}
