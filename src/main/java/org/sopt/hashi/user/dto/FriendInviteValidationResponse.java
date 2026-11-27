package org.sopt.hashi.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "Validation")
public record FriendInviteValidationResponse(boolean valid) {}
