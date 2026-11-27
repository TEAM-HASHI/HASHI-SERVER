package org.sopt.hashi.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(name = "Code")
public record ValidateFriendInviteCodeRequest(@NotBlank @Size(max = 10) String inviteCode) {}
