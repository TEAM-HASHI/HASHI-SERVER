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
    public SuccessResponse<List<ExchangeRatePort.Revision>> history(@RequestParam(required = false) @Positive Long beforeId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return SuccessResponse.of(CommonSuccessCode.OK, rates.history(beforeId, size));
    }

    public record Correction(@NotNull LocalDate quotedDate,
            @NotNull @Positive @Digits(integer = 8, fraction = 4) BigDecimal krwPer100Jpy) {}
}
