package org.sopt.hashi.admin.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;

public record AdminExchangeRateCorrectionRequest(
        @NotNull LocalDate quotedDate,
        @NotNull @Positive @Digits(integer = 8, fraction = 4) BigDecimal krwPer100Jpy) {}
