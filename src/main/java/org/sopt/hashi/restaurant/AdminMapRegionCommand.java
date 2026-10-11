package org.sopt.hashi.restaurant;

import java.math.BigDecimal;

/** 관광 지역의 전체 설정. code는 URL에서 받고 변경하지 않는다. */
public record AdminMapRegionCommand(String name, BigDecimal latitude, BigDecimal longitude,
                                    BigDecimal south, BigDecimal north, BigDecimal west, BigDecimal east,
                                    int displayOrder, boolean active) {
}
