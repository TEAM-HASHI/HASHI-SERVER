package org.sopt.hashi.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

@Schema(name = "Issue")
public record IssueSignupPhoneChallengeRequest(@NotNull @Pattern(regexp = "010[0-9]{8}") String phone) {}
