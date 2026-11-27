package org.sopt.hashi.admin.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.sopt.hashi.exchangerate.ExchangeRatePort;
import org.sopt.hashi.shared.error.CommonSuccessCode;
import org.sopt.hashi.shared.response.SuccessResponse;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/exchange-rates/jpy")
public class AdminExchangeRateController {
    private final ExchangeRatePort rates;

    public AdminExchangeRateController(ExchangeRatePort rates) {
        this.rates = rates;
    }

    @PutMapping("/{appliedDate}")
    public SuccessResponse<ExchangeRatePort.Revision> correct(@PathVariable LocalDate appliedDate,
            @Valid @RequestBody Correction request) {
        return SuccessResponse.of(CommonSuccessCode.OK, rates.correct(appliedDate, request.quotedDate(), request.krwPer100Jpy()));
    }

    @GetMapping("/history")
    public SuccessResponse<HistoryResponse> history(@RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return SuccessResponse.of(CommonSuccessCode.OK, HistoryResponse.from(rates.history(page, size)));
    }

    public record HistoryResponse(List<ExchangeRatePort.Revision> revisions, int page, int size,
                                  long totalCount, int totalPages) {
        static HistoryResponse from(Page<ExchangeRatePort.Revision> page) {
            return new HistoryResponse(page.getContent(), page.getNumber(), page.getSize(),
                    page.getTotalElements(), page.getTotalPages());
        }
    }

    public record Correction(@NotNull LocalDate quotedDate,
            @NotNull @Positive @Digits(integer = 8, fraction = 4) BigDecimal krwPer100Jpy) {}
}
