package org.sopt.hashi.admin.service;

import org.sopt.hashi.admin.dto.AdminMapRegionResponse;
import org.sopt.hashi.admin.dto.SetRestaurantMapRegionRequest;
import org.sopt.hashi.admin.dto.UpsertMapRegionRequest;
import org.sopt.hashi.restaurant.AdminMapRegionCommand;
import org.sopt.hashi.restaurant.RestaurantPort;
import org.springframework.stereotype.Service;

/** 진입점은 DTO 변환과 Port 위임만 담당한다. */
@Service
public class AdminMapRegionService {
    private final RestaurantPort port;

    public AdminMapRegionService(RestaurantPort port) {
        this.port = port;
    }

    public AdminMapRegionResponse.Page getRegions(int page, int size) {
        return AdminMapRegionResponse.Page.from(port.getMapRegionsByAdmin(page, size));
    }

    public AdminMapRegionResponse upsert(String code, UpsertMapRegionRequest request) {
        var position = request.clusterPosition();
        var bounds = request.cameraBounds();
        var command = new AdminMapRegionCommand(request.name(), position.latitude(), position.longitude(),
                bounds.south(), bounds.north(), bounds.west(), bounds.east(), request.displayOrder(), request.active());
        return AdminMapRegionResponse.from(port.upsertMapRegionByAdmin(code, command));
    }

    public AdminMapRegionResponse.Assignment assign(Long restaurantId, SetRestaurantMapRegionRequest request) {
        return new AdminMapRegionResponse.Assignment(restaurantId,
                port.assignMapRegionByAdmin(restaurantId, request.mapRegionId()));
    }
}
