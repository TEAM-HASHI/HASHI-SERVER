package org.sopt.hashi.support.web;

import org.sopt.hashi.shared.response.SuccessResponse;
import org.sopt.hashi.shared.swagger.ApiException;
import org.sopt.hashi.shared.swagger.ApiSuccess;
import org.sopt.hashi.support.NoticeInfo;
import org.sopt.hashi.support.code.SupportErrorCode;
import org.sopt.hashi.support.code.SupportSuccessCode;
import org.sopt.hashi.support.dto.NoticeListResponse;
import org.sopt.hashi.support.service.NoticeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 비로그인 사용자도 게시된 공지를 조회할 수 있다. */
@RestController
@RequestMapping("/api/v1/notices")
public class NoticeController {
    private final NoticeService service;

    public NoticeController(NoticeService service) { this.service = service; }

    @GetMapping
    @ApiSuccess(value = SupportSuccessCode.class, codes = "NOTICE_READ")
    @ApiException(value = SupportErrorCode.class, codes = "INVALID_CURSOR")
    public SuccessResponse<NoticeListResponse> list(@RequestParam(required = false) String cursor) {
        return SuccessResponse.of(SupportSuccessCode.NOTICE_READ, service.list(cursor));
    }

    @GetMapping("/{noticeId}")
    @ApiSuccess(value = SupportSuccessCode.class, codes = "NOTICE_READ")
    @ApiException(value = SupportErrorCode.class, codes = "NOTICE_NOT_FOUND")
    public SuccessResponse<NoticeInfo> detail(@PathVariable Long noticeId) {
        return SuccessResponse.of(SupportSuccessCode.NOTICE_READ, service.detail(noticeId));
    }
}
