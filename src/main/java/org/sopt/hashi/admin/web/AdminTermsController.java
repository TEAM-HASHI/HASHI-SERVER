package org.sopt.hashi.admin.web;

import java.util.List;
import org.sopt.hashi.admin.code.AdminSuccessCode;
import org.sopt.hashi.admin.service.AdminTermsService;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.shared.error.CommonSuccessCode;
import org.sopt.hashi.shared.response.SuccessResponse;
import org.sopt.hashi.shared.swagger.ApiException;
import org.sopt.hashi.shared.swagger.ApiSuccess;
import jakarta.validation.Valid;
import org.sopt.hashi.admin.dto.SaveTermsRequest;
import org.sopt.hashi.admin.dto.AdminTermsResponse;
import org.sopt.hashi.admin.dto.AdminTermsListResponse;
import org.sopt.hashi.support.TermsType;
import org.sopt.hashi.admin.dto.AdminTermsTypeResponse;
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

@RestController
@RequestMapping("/api/v1/admin/terms")
public class AdminTermsController {
    private final AdminTermsService service;
    public AdminTermsController(AdminTermsService service) { this.service = service; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @ApiException(value = CommonErrorCode.class, codes = {"UNAUTHORIZED", "FORBIDDEN", "INVALID_INPUT"})
    @ApiSuccess(value = AdminSuccessCode.class, codes = "TERMS_CREATED")
    public SuccessResponse<AdminTermsResponse> create(@Valid @RequestBody SaveTermsRequest request) {
        return SuccessResponse.of(AdminSuccessCode.TERMS_CREATED, service.create(request.toCommand()));
    }

    @PutMapping("/{termsId}")
    @ApiException(value = CommonErrorCode.class, codes = {"UNAUTHORIZED", "FORBIDDEN", "INVALID_INPUT"})
    @ApiSuccess(value = AdminSuccessCode.class, codes = "TERMS_UPDATED")
    public SuccessResponse<AdminTermsResponse> update(@PathVariable Long termsId, @Valid @RequestBody SaveTermsRequest request) {
        return SuccessResponse.of(AdminSuccessCode.TERMS_UPDATED, service.update(termsId, request.toCommand()));
    }

    @PostMapping("/{termsId}/publication")
    @ApiException(value = CommonErrorCode.class, codes = {"UNAUTHORIZED", "FORBIDDEN"})
    @ApiSuccess(value = AdminSuccessCode.class, codes = "TERMS_PUBLISHED")
    public SuccessResponse<AdminTermsResponse> publish(@PathVariable Long termsId) {
        return SuccessResponse.of(AdminSuccessCode.TERMS_PUBLISHED, service.publish(termsId));
    }

    @DeleteMapping("/{termsId}")
    @ApiException(value = CommonErrorCode.class, codes = {"UNAUTHORIZED", "FORBIDDEN"})
    @ApiSuccess(value = AdminSuccessCode.class, codes = "TERMS_DELETED")
    public SuccessResponse<Void> delete(@PathVariable Long termsId) {
        service.delete(termsId);
        return SuccessResponse.of(AdminSuccessCode.TERMS_DELETED, null);
    }

    @GetMapping("/{termsId}")
    @ApiException(value = CommonErrorCode.class, codes = {"UNAUTHORIZED", "FORBIDDEN"})
    @ApiSuccess(value = CommonSuccessCode.class, codes = "OK")
    public SuccessResponse<AdminTermsResponse> detail(@PathVariable Long termsId) {
        return SuccessResponse.of(CommonSuccessCode.OK, service.detail(termsId));
    }

    @GetMapping
    @ApiException(value = CommonErrorCode.class, codes = {"UNAUTHORIZED", "FORBIDDEN", "INVALID_INPUT"})
    @ApiSuccess(value = CommonSuccessCode.class, codes = "OK")
    public SuccessResponse<AdminTermsListResponse> history(@RequestParam TermsType type,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return SuccessResponse.of(CommonSuccessCode.OK, service.history(type, page, size));
    }

    @GetMapping("/types")
    @ApiException(value = CommonErrorCode.class, codes = {"UNAUTHORIZED", "FORBIDDEN"})
    @ApiSuccess(value = CommonSuccessCode.class, codes = "OK")
    public SuccessResponse<List<AdminTermsTypeResponse>> types() {
        return SuccessResponse.of(CommonSuccessCode.OK, service.types());
    }
}
