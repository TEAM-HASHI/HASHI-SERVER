package org.sopt.hashi.admin.web;

import org.sopt.hashi.admin.dto.AdminUserListResponse;
import org.sopt.hashi.admin.service.AdminUserService;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.shared.error.CommonSuccessCode;
import org.sopt.hashi.shared.response.SuccessResponse;
import org.sopt.hashi.shared.swagger.ApiException;
import org.sopt.hashi.user.AdminUserSortType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 어드민 회원 관리 API — 회원 목록 조회. */
@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {

    private final AdminUserService adminUserService;

    public AdminUserController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    /**
     * 회원 목록 조회 — offset 페이지네이션(page는 0부터).
     * 기본 정렬은 닉네임 가나다순, sort=CREATED_AT이면 가입일 최신순. keyword는 닉네임 부분 일치 검색.
     */
    @ApiException(value = CommonErrorCode.class, codes = {"UNAUTHORIZED", "FORBIDDEN"})
    @GetMapping
    public SuccessResponse<AdminUserListResponse> getUsers(
            @RequestParam(defaultValue = "NICKNAME") AdminUserSortType sort,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return SuccessResponse.of(CommonSuccessCode.OK,
                adminUserService.getUsers(sort, keyword, page, size));
    }
}
