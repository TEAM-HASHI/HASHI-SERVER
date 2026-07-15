package org.sopt.hashi.upload.web;

import jakarta.validation.Valid;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.shared.error.CommonSuccessCode;
import org.sopt.hashi.shared.response.SuccessResponse;
import org.sopt.hashi.shared.swagger.ApiException;
import org.sopt.hashi.upload.code.UploadErrorCode;
import org.sopt.hashi.upload.dto.IssuePresignedUrlsRequest;
import org.sopt.hashi.upload.dto.PresignedUrlsResponse;
import org.sopt.hashi.upload.service.UploadService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 파일 업로드 API — presigned URL 발급. */
@RestController
@RequestMapping("/api/v1/uploads")
public class UploadController {

    private final UploadService uploadService;

    public UploadController(UploadService uploadService) {
        this.uploadService = uploadService;
    }

    /** 업로드용 presigned URL 벌크 발급 — 각 URL로 파일을 PUT한 뒤, 응답의 key를 등록 API에 전달한다. */
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT", "UNAUTHORIZED"})
    @ApiException(value = UploadErrorCode.class,
            codes = {"UNSUPPORTED_USAGE", "UNSUPPORTED_FILE_TYPE", "FILE_SIZE_EXCEEDED"})
    @PostMapping("/presigned-urls")
    public SuccessResponse<PresignedUrlsResponse> issuePresignedUrls(
            @Valid @RequestBody IssuePresignedUrlsRequest request) {
        return SuccessResponse.of(CommonSuccessCode.OK, uploadService.issuePresignedUrls(request));
    }
}
