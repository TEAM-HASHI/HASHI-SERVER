package org.sopt.hashi.admin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.List;
import org.sopt.hashi.restaurant.AdminMapRegionInfo;

public record AdminMapRegionResponse(Long mapRegionId, String code, String name, Position clusterPosition,
                                     Bounds cameraBounds, int displayOrder, boolean active) {
    public static AdminMapRegionResponse from(AdminMapRegionInfo info) {
        return new AdminMapRegionResponse(info.mapRegionId(), info.code(), info.name(),
                new Position(info.latitude(), info.longitude()),
                new Bounds(info.south(), info.north(), info.west(), info.east()), info.displayOrder(), info.active());
    }

    public record Position(BigDecimal latitude, BigDecimal longitude) {
    }

    public record Bounds(BigDecimal south, BigDecimal north, BigDecimal west, BigDecimal east) {
    }

    public record Page(List<AdminMapRegionResponse> content, int page, int size, long totalElements, int totalPages) {
        public static Page from(AdminMapRegionInfo.Page info) {
            return new Page(info.content().stream().map(AdminMapRegionResponse::from).toList(),
                    info.page(), info.size(), info.totalElements(), info.totalPages());
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Assignment(Long restaurantId, Long mapRegionId) {
    }
}
