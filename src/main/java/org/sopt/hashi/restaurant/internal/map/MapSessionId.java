package org.sopt.hashi.restaurant.internal.map;

import java.util.UUID;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;

/** 조회 ID 하나가 Redis 키 하나를 식별한다. */
public record MapSessionId(UUID id) {
    public MapSessionId {
        if (id == null) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
    }

    public String value() {
        return id.toString();
    }

    public static MapSessionId parse(String value) {
        if (value == null || !value.matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        return new MapSessionId(UUID.fromString(value));
    }
}
