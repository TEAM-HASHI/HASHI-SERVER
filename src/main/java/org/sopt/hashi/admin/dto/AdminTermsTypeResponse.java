package org.sopt.hashi.admin.dto;

import org.sopt.hashi.support.TermsType;
import org.sopt.hashi.support.TermsTypeInfo;

public record AdminTermsTypeResponse(TermsType type, String title, Long currentTermsId) {
    public static AdminTermsTypeResponse from(TermsTypeInfo info) {
        return new AdminTermsTypeResponse(info.type(), info.title(), info.currentTermsId());
    }
}
