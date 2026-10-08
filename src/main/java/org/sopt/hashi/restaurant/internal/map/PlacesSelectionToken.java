package org.sopt.hashi.restaurant.internal.map;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** 후보 원문을 저장하지 않고 restaurant/revision/request/place를 10분 동안 묶는 서명 토큰. */
@Component
public class PlacesSelectionToken {
    private static final String HMAC = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private final PlacesSelectionProperties properties;
    private final Clock clock;

    public PlacesSelectionToken(PlacesSelectionProperties properties, @Qualifier("japanClock") Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public boolean isConfigured() {
        return properties.isConfigured();
    }

    public Issued issue(Long restaurantId, long addressRevision, UUID requestId, String placeId) {
        if (!isConfigured()) {
            throw new IllegalStateException("Places selection signing is not configured");
        }
        Instant issuedAt = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant expiresAt = issuedAt.plus(properties.tokenTtl());
        String encodedPlaceId = ENCODER.encodeToString(placeId.getBytes(StandardCharsets.UTF_8));
        String payload = String.join("|", "1", restaurantId.toString(), Long.toString(addressRevision),
                requestId.toString(), encodedPlaceId, Long.toString(issuedAt.getEpochSecond()),
                Long.toString(expiresAt.getEpochSecond()));
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        String value = ENCODER.encodeToString(payloadBytes) + "." + ENCODER.encodeToString(sign(payloadBytes));
        return new Issued(value, expiresAt);
    }

    public Optional<Claims> verify(String token) {
        if (!isConfigured() || token == null || token.length() > 2048) {
            return Optional.empty();
        }
        try {
            String[] tokenParts = token.split("\\.", -1);
            if (tokenParts.length != 2) {
                return Optional.empty();
            }
            byte[] payloadBytes = DECODER.decode(tokenParts[0]);
            byte[] suppliedSignature = DECODER.decode(tokenParts[1]);
            if (!tokenParts[0].equals(ENCODER.encodeToString(payloadBytes))
                    || !tokenParts[1].equals(ENCODER.encodeToString(suppliedSignature))) {
                return Optional.empty();
            }
            if (!MessageDigest.isEqual(sign(payloadBytes), suppliedSignature)) {
                return Optional.empty();
            }
            String[] claims = new String(payloadBytes, StandardCharsets.UTF_8).split("\\|", -1);
            if (claims.length != 7 || !"1".equals(claims[0])) {
                return Optional.empty();
            }
            Claims parsed = new Claims(Long.valueOf(claims[1]), Long.parseLong(claims[2]),
                    UUID.fromString(claims[3]), new String(DECODER.decode(claims[4]), StandardCharsets.UTF_8),
                    Instant.ofEpochSecond(Long.parseLong(claims[5])), Instant.ofEpochSecond(Long.parseLong(claims[6])));
            Instant now = clock.instant();
            if (parsed.issuedAt().isAfter(now) || !now.isBefore(parsed.expiresAt())
                    || !parsed.expiresAt().equals(parsed.issuedAt().plus(properties.tokenTtl()))
                    || parsed.placeId().isBlank() || !parsed.placeId().equals(parsed.placeId().trim())
                    || parsed.placeId().length() > 255) {
                return Optional.empty();
            }
            return Optional.of(parsed);
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(properties.signingKeyBytes(), HMAC));
            return mac.doFinal(payload);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Places selection signing is unavailable", exception);
        }
    }

    public record Issued(String token, Instant expiresAt) {
    }

    public record Claims(Long restaurantId, long addressRevision, UUID requestId, String placeId,
                         Instant issuedAt, Instant expiresAt) {
        @Override
        public String toString() {
            return "PlacesSelectionClaims[restaurantId=" + restaurantId + ",addressRevision="
                    + addressRevision + "]";
        }
    }
}
