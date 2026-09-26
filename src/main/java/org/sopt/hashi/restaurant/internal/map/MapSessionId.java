package org.sopt.hashi.restaurant.internal.map;

import java.util.UUID;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;

/** 슬롯 재사용 시 UUID까지 비교하므로 이전 세션을 새 세션에 연결하지 않는다. */
public record MapSessionId(int slot, UUID id) {
    public MapSessionId {
        if (slot < 0 || slot >= MapQuerySession.MAX_SESSIONS || id == null) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
    }

    public String value() {
        return slot + "_" + id;
    }

    public static MapSessionId parse(String value) {
        if (value == null || !value.matches("(?:0|[1-9][0-9]{0,2})_[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        int separator = value.indexOf('_');
        return new MapSessionId(Integer.parseInt(value.substring(0, separator)),
                UUID.fromString(value.substring(separator + 1)));
    }
}
