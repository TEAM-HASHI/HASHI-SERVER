package org.sopt.hashi.restaurant.internal.map;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import org.sopt.hashi.restaurant.code.RestaurantErrorCode;
import org.sopt.hashi.restaurant.domain.RestaurantMapSort;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.springframework.stereotype.Component;

@Component
public class MapCursorCodec {
    private static final int VERSION = 2;
    private static final int PAYLOAD_SIZE = 22;
    private static final int SIGNATURE_SIZE = 32;
    private final MapSessionProperties properties;

    public MapCursorCodec(MapSessionProperties properties) {
        this.properties = properties;
    }

    public void requireConfigured() {
        properties.requireConfigured();
    }

    public String encode(MapSessionId session, RestaurantMapSort sort, int position) {
        validatePosition(position);
        ByteBuffer bytes = ByteBuffer.allocate(PAYLOAD_SIZE + SIGNATURE_SIZE);
        bytes.put((byte) VERSION).putLong(session.id().getMostSignificantBits())
                .putLong(session.id().getLeastSignificantBits()).put((byte) sort.ordinal()).putInt(position);
        bytes.put(sign(Arrays.copyOf(bytes.array(), PAYLOAD_SIZE)));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.array());
    }

    public DecodedCursor decode(String token) {
        if (token == null || token.length() > 512 || !token.matches("[A-Za-z0-9_-]{72}")) {
            throw invalid();
        }
        byte[] bytes;
        try {
            bytes = Base64.getUrlDecoder().decode(token);
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
        boolean canonical = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(token);
        if (!canonical || !MessageDigest.isEqual(sign(Arrays.copyOf(bytes, PAYLOAD_SIZE)),
                Arrays.copyOfRange(bytes, PAYLOAD_SIZE, bytes.length))) {
            throw invalid();
        }
        ByteBuffer payload = ByteBuffer.wrap(bytes);
        int version = Byte.toUnsignedInt(payload.get());
        UUID id = new UUID(payload.getLong(), payload.getLong());
        int sort = Byte.toUnsignedInt(payload.get());
        int position = payload.getInt();
        if (version != VERSION || sort >= RestaurantMapSort.values().length) {
            throw invalid();
        }
        validatePosition(position);
        return new DecodedCursor(new MapSessionId(id), RestaurantMapSort.values()[sort], position);
    }

    public String callerKey(String remoteAddress) {
        String address = remoteAddress == null ? "unknown" : remoteAddress;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                sign(("map-caller:" + address).getBytes(StandardCharsets.UTF_8)));
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(properties.requireSigningKey());
            return mac.doFinal(payload);
        } catch (GeneralSecurityException exception) {
            throw new BusinessException(RestaurantErrorCode.MAP_SESSION_UNAVAILABLE);
        }
    }

    private static void validatePosition(int position) {
        if (position < 1 || position > MapQuerySession.MAX_CANDIDATES) {
            throw invalid();
        }
    }

    private static BusinessException invalid() {
        return new BusinessException(CommonErrorCode.INVALID_INPUT);
    }

    public record DecodedCursor(MapSessionId session, RestaurantMapSort sort, int position) {
    }
}
