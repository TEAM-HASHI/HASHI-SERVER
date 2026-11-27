package org.sopt.hashi.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(name = "Challenge")
public record SignupPhoneChallengeResponse(
        UUID challengeId, String recipient, String text, int expiresInSeconds) {}
