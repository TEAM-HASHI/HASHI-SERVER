package org.sopt.hashi.admin.web;

import java.util.List;
import org.sopt.hashi.admin.code.AdminSuccessCode;
import org.sopt.hashi.admin.service.AdminNoticeService;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.shared.error.CommonSuccessCode;
import org.sopt.hashi.shared.response.SuccessResponse;
import org.sopt.hashi.shared.swagger.ApiErrorResponse;
import org.sopt.hashi.shared.swagger.ApiException;
import org.sopt.hashi.shared.swagger.ApiSuccess;
import jakarta.validation.Valid;
import org.sopt.hashi.admin.dto.SaveNoticeRequest;
import org.sopt.hashi.support.NoticeInfo;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** ADMIN 전용 공지 관리. PUT은 전체 내용·이미지 순서 교체다. */
@RestController
@RequestMapping("/api/v1/admin/notices")
public class AdminNoticeController {
    private final AdminNoticeService service;
    public AdminNoticeController(AdminNoticeService service) { this.service = service; }

    @ApiException(value = CommonErrorCode.class, codes = {"UNAUTHORIZED", "FORBIDDEN", "INVALID_INPUT"})
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @ApiSuccess(value = AdminSuccessCode.class, codes = "NOTICE_CREATED")
    @ApiErrorResponse(status = HttpStatus.BAD_REQUEST, code = "SUPPORT-401", message = "공지사항 입력값이 올바르지 않습니다")
    public SuccessResponse<NoticeInfo> create(@Valid @RequestBody SaveNoticeRequest request) {
        return SuccessResponse.of(AdminSuccessCode.NOTICE_CREATED, service.create(request.toCommand()));
    }

    @ApiException(value = CommonErrorCode.class, codes = {"UNAUTHORIZED", "FORBIDDEN", "INVALID_INPUT"})
    @PutMapping("/{noticeId}")
    @ApiSuccess(value = AdminSuccessCode.class, codes = "NOTICE_UPDATED")
    @ApiErrorResponse(status = HttpStatus.NOT_FOUND, code = "SUPPORT-400", message = "공지사항을 찾을 수 없습니다")
    public SuccessResponse<NoticeInfo> update(@PathVariable Long noticeId, @Valid @RequestBody SaveNoticeRequest request) {
        return SuccessResponse.of(AdminSuccessCode.NOTICE_UPDATED, service.update(noticeId, request.toCommand()));
    }

    @ApiException(value = CommonErrorCode.class, codes = {"UNAUTHORIZED", "FORBIDDEN", "INVALID_INPUT"})
    @PostMapping("/{noticeId}/publication")
    @ApiSuccess(value = AdminSuccessCode.class, codes = "NOTICE_PUBLISHED")
    public SuccessResponse<NoticeInfo> publish(@PathVariable Long noticeId) {
        return SuccessResponse.of(AdminSuccessCode.NOTICE_PUBLISHED, service.publish(noticeId));
    }

    @ApiException(value = CommonErrorCode.class, codes = {"UNAUTHORIZED", "FORBIDDEN"})
    @DeleteMapping("/{noticeId}")
    @ApiSuccess(value = AdminSuccessCode.class, codes = "NOTICE_DELETED")
    public SuccessResponse<Void> delete(@PathVariable Long noticeId) {
        service.delete(noticeId);
        return SuccessResponse.of(AdminSuccessCode.NOTICE_DELETED, null);
    }

    @ApiException(value = CommonErrorCode.class, codes = {"UNAUTHORIZED", "FORBIDDEN"})
    @GetMapping("/{noticeId}")
    @ApiSuccess(value = CommonSuccessCode.class, codes = "OK")
    public SuccessResponse<NoticeInfo> detail(@PathVariable Long noticeId) {
        return SuccessResponse.of(CommonSuccessCode.OK, service.detail(noticeId));
    }

    @ApiException(value = CommonErrorCode.class, codes = {"UNAUTHORIZED", "FORBIDDEN"})
    @GetMapping
    @ApiSuccess(value = CommonSuccessCode.class, codes = "OK")
    public SuccessResponse<List<NoticeInfo>> list(@RequestParam(required = false) Long beforeId) {
        return SuccessResponse.of(CommonSuccessCode.OK, service.list(beforeId));
    }
}
