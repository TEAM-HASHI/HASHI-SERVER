package org.sopt.hashi.restaurant;

import java.math.BigDecimal;
import java.util.List;

/** 엔티티·Spring Page를 노출하지 않는 관리자 지역 계약. */
public record AdminMapRegionInfo(Long mapRegionId, String code, String name,
                                 BigDecimal latitude, BigDecimal longitude,
                                 BigDecimal south, BigDecimal north, BigDecimal west, BigDecimal east,
                                 int displayOrder, boolean active) {
    public record Page(List<AdminMapRegionInfo> content, int page, int size, long totalElements, int totalPages) {
        public Page {
            content = List.copyOf(content);
        }
    }
}
