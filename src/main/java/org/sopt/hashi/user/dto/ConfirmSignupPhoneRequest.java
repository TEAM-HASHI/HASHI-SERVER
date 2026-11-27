package org.sopt.hashi.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

@Schema(name = "Confirm")
public record ConfirmSignupPhoneRequest(
        @NotNull @Pattern(regexp = "010[0-9]{8}") String phone,
        @NotNull UUID challengeId) {}
