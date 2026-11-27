package org.sopt.hashi.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "Result")
public record SignupPhoneConfirmationResponse(boolean verified) {}
