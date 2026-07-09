package org.sopt.hashi.upload.web;

import jakarta.validation.Valid;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.shared.error.CommonSuccessCode;
import org.sopt.hashi.shared.response.SuccessResponse;
import org.sopt.hashi.shared.swagger.ApiException;
import org.sopt.hashi.upload.code.UploadErrorCode;
import org.sopt.hashi.upload.dto.IssuePresignedUrlRequest;
import org.sopt.hashi.upload.dto.PresignedUrlResponse;
import org.sopt.hashi.upload.service.UploadService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/uploads")
public class UploadController {

    private final UploadService uploadService;

    public UploadController(UploadService uploadService) {
        this.uploadService = uploadService;
    }

    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT", "UNAUTHORIZED"})
    @ApiException(value = UploadErrorCode.class,
            codes = {"UNSUPPORTED_USAGE", "UNSUPPORTED_FILE_TYPE", "FILE_SIZE_EXCEEDED"})
    @PostMapping("/presigned-urls")
    public SuccessResponse<PresignedUrlResponse> issuePresignedUrl(
            @Valid @RequestBody IssuePresignedUrlRequest request) {
        return SuccessResponse.of(CommonSuccessCode.OK, uploadService.issuePresignedUrl(request));
    }
}
