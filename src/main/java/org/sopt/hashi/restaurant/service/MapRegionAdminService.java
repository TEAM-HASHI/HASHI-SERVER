package org.sopt.hashi.restaurant.service;

import org.sopt.hashi.restaurant.AdminMapRegionCommand;
import org.sopt.hashi.restaurant.AdminMapRegionInfo;
import org.sopt.hashi.restaurant.code.RestaurantErrorCode;
import org.sopt.hashi.restaurant.domain.MapBounds;
import org.sopt.hashi.restaurant.domain.MapCoordinates;
import org.sopt.hashi.restaurant.domain.MapQueryBounds;
import org.sopt.hashi.restaurant.domain.MapRegion;
import org.sopt.hashi.restaurant.domain.MapRegionRepository;
import org.sopt.hashi.restaurant.domain.Restaurant;
import org.sopt.hashi.restaurant.domain.RestaurantRepository;
import org.sopt.hashi.restaurant.internal.map.MapQueryProperties;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 관리자 쓰기에서도 MapRegion 값 검증과 기존 식당 잠금 순서를 유지한다. */
@Service
@Transactional(readOnly = true)
public class MapRegionAdminService {
    private static final int MAX_PAGE_SIZE = 100;

    private final MapRegionRepository regions;
    private final RestaurantRepository restaurants;
    private final MapQueryProperties properties;

    public MapRegionAdminService(MapRegionRepository regions, RestaurantRepository restaurants,
                                  MapQueryProperties properties) {
        this.regions = regions;
        this.restaurants = restaurants;
        this.properties = properties;
    }

    public AdminMapRegionInfo.Page getRegions(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        var result = regions.findAll(PageRequest.of(page, size, Sort.by("displayOrder", "id")));
        return new AdminMapRegionInfo.Page(result.map(this::toInfo).getContent(), page, size,
                result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AdminMapRegionInfo upsert(String code, AdminMapRegionCommand command) {
        MapRegion region = validatedRegion(code, command);
        if (region.isActive()) {
            MapBounds bounds = region.getCameraBounds();
            new MapQueryBounds(bounds.getSouth(), bounds.getNorth(), bounds.getWest(), bounds.getEast())
                    .validateQueryWithin(properties.requireConfiguration().supportedBounds());
        }
        regions.upsert(region);
        return toInfo(regions.findByCode(code).orElseThrow());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Long assign(Long restaurantId, Long mapRegionId) {
        boolean invalidIds = restaurantId == null || restaurantId <= 0 || (mapRegionId != null && mapRegionId <= 0);
        if (invalidIds) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        Restaurant restaurant = restaurants.findByIdForUpdate(restaurantId)
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> new BusinessException(RestaurantErrorCode.NOT_FOUND));
        if (mapRegionId != null && !regions.existsById(mapRegionId)) {
            throw new BusinessException(RestaurantErrorCode.MAP_REGION_NOT_FOUND);
        }
        // 비활성 지역에도 미리 소속을 입력할 수 있다. 좌표나 cameraBounds로 자동 분류하지 않는다.
        restaurant.assignMapRegion(mapRegionId);
        return restaurant.getMapRegionId();
    }

    private MapRegion validatedRegion(String code, AdminMapRegionCommand command) {
        if (command == null) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        try {
            MapRegion region = MapRegion.create(code, command.name(),
                    MapCoordinates.of(command.latitude(), command.longitude()),
                    MapBounds.of(command.south(), command.north(), command.west(), command.east()),
                    command.displayOrder());
            if (command.active()) {
                region.activate();
            }
            return region;
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
    }

    private AdminMapRegionInfo toInfo(MapRegion region) {
        MapBounds bounds = region.getCameraBounds();
        return new AdminMapRegionInfo(region.getId(), region.getCode(), region.getName(),
                region.getClusterPosition().getLatitude(), region.getClusterPosition().getLongitude(),
                bounds.getSouth(), bounds.getNorth(), bounds.getWest(), bounds.getEast(),
                region.getDisplayOrder(), region.isActive());
    }
}
