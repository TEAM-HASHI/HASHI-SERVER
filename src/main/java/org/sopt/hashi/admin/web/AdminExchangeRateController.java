package org.sopt.hashi.admin.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import org.sopt.hashi.admin.code.AdminSuccessCode;
import org.sopt.hashi.admin.dto.AdminExchangeRateCorrectionRequest;
import org.sopt.hashi.admin.dto.AdminExchangeRateHistoryResponse;
import org.sopt.hashi.admin.dto.AdminExchangeRateRevisionResponse;
import org.sopt.hashi.exchangerate.ExchangeRatePort;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.shared.error.CommonSuccessCode;
import org.sopt.hashi.shared.response.SuccessResponse;
import org.sopt.hashi.shared.swagger.ApiException;
import org.sopt.hashi.shared.swagger.ApiSuccess;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/exchange-rates/jpy")
public class AdminExchangeRateController {
    private final ExchangeRatePort rates;

    public AdminExchangeRateController(ExchangeRatePort rates) {
        this.rates = rates;
    }

    @PutMapping("/{appliedDate}")
    @ApiException(value = CommonErrorCode.class, codes = {"UNAUTHORIZED", "FORBIDDEN", "INVALID_INPUT"})
    @ApiSuccess(value = AdminSuccessCode.class, codes = "EXCHANGE_RATE_CORRECTED")
    public SuccessResponse<AdminExchangeRateRevisionResponse> correct(@PathVariable LocalDate appliedDate,
            @Valid @RequestBody AdminExchangeRateCorrectionRequest request) {
        var revision = rates.correct(appliedDate, request.quotedDate(), request.krwPer100Jpy());
        return SuccessResponse.of(AdminSuccessCode.EXCHANGE_RATE_CORRECTED,
                AdminExchangeRateRevisionResponse.from(revision));
    }

    @GetMapping("/history")
    @ApiException(value = CommonErrorCode.class, codes = {"UNAUTHORIZED", "FORBIDDEN", "INVALID_INPUT"})
    @ApiSuccess(value = CommonSuccessCode.class, codes = "OK")
    public SuccessResponse<AdminExchangeRateHistoryResponse> history(@RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return SuccessResponse.of(CommonSuccessCode.OK, AdminExchangeRateHistoryResponse.from(rates.history(page, size)));
    }
}
