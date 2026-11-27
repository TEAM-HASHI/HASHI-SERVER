package org.sopt.hashi.admin.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;

public record AdminExchangeRateCorrectionRequest(
        @NotNull(message = "고시일을 입력해 주세요.") LocalDate quotedDate,
        @NotNull(message = "100엔당 원화 환율을 입력해 주세요.")
        @Positive(message = "환율은 0보다 커야 합니다.")
        @Digits(integer = 8, fraction = 4, message = "환율은 정수부 8자리, 소수부 4자리까지 입력할 수 있습니다.")
        BigDecimal krwPer100Jpy) {}
