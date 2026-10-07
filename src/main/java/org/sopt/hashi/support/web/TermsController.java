package org.sopt.hashi.support.web;

import java.util.List;
import org.sopt.hashi.shared.response.SuccessResponse;
import org.sopt.hashi.shared.swagger.ApiException;
import org.sopt.hashi.shared.swagger.ApiSuccess;
import org.sopt.hashi.support.dto.TermsResponse;
import org.sopt.hashi.support.dto.TermsSummaryResponse;
import org.sopt.hashi.support.code.SupportErrorCode;
import org.sopt.hashi.support.code.SupportSuccessCode;
import org.sopt.hashi.support.service.TermsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/terms")
public class TermsController {
    private final TermsService service;
    public TermsController(TermsService service) { this.service = service; }

    @GetMapping
    @ApiSuccess(value = SupportSuccessCode.class, codes = "TERMS_READ")
    public SuccessResponse<List<TermsSummaryResponse>> list() {
        return SuccessResponse.of(SupportSuccessCode.TERMS_READ, service.currentList().stream().map(TermsSummaryResponse::from).toList());
    }

    @GetMapping("/{termsId}")
    @ApiSuccess(value = SupportSuccessCode.class, codes = "TERMS_READ")
    @ApiException(value = SupportErrorCode.class, codes = "TERMS_NOT_FOUND")
    public SuccessResponse<TermsResponse> detail(@PathVariable Long termsId) {
        return SuccessResponse.of(SupportSuccessCode.TERMS_READ, TermsResponse.from(service.currentDetail(termsId)));
    }
}
