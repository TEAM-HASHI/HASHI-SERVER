package org.sopt.hashi.admin.service;

import java.util.List;
import org.sopt.hashi.admin.dto.AdminRestaurantResponse;
import org.sopt.hashi.admin.dto.CreateRestaurantRequest;
import org.sopt.hashi.admin.dto.UpdateRestaurantRequest;
import org.sopt.hashi.restaurant.AdminRestaurantCommand;
import org.sopt.hashi.restaurant.AdminRestaurantCommand.BusinessHourCommand;
import org.sopt.hashi.restaurant.AdminRestaurantCommand.MenuCommand;
import org.sopt.hashi.restaurant.RestaurantPort;
import org.springframework.stereotype.Service;

/**
 * 어드민 식당 관리 — 진입점 모듈이라 도메인 로직 없이 {@link RestaurantPort}로 위임하고
 * 요청→커맨드·응답 DTO 변환만 한다(architecture.md §9). 저장·검증·URL 변환은 restaurant 소관.
 */
@Service
public class AdminRestaurantService {

    private final RestaurantPort restaurantPort;

    public AdminRestaurantService(RestaurantPort restaurantPort) {
        this.restaurantPort = restaurantPort;
    }

    public AdminRestaurantResponse create(CreateRestaurantRequest request) {
        return AdminRestaurantResponse.from(restaurantPort.createByAdmin(toCommand(request)));
    }

    public AdminRestaurantResponse update(Long restaurantId, UpdateRestaurantRequest request) {
        return AdminRestaurantResponse.from(restaurantPort.updateByAdmin(restaurantId, toCommand(request)));
    }

    public void delete(Long restaurantId) {
        restaurantPort.deleteByAdmin(restaurantId);
    }

    private AdminRestaurantCommand toCommand(CreateRestaurantRequest request) {
        return new AdminRestaurantCommand(
                request.name(),
                request.localName(),
                request.summary(),
                request.description(),
                request.address(),
                request.area(),
                request.genre(),
                request.foodCategory(),
                request.priceCurrency(),
                request.minPrice(),
                request.maxPrice(),
                request.imageKeys(),
                toMenuCommands(request.menus()),
                request.hashtags(),
                request.curationTypes(),
                toBusinessHourCommands(request.businessHours()));
    }

    private AdminRestaurantCommand toCommand(UpdateRestaurantRequest request) {
        return new AdminRestaurantCommand(
                request.name(),
                request.localName(),
                request.summary(),
                request.description(),
                request.address(),
                request.area(),
                request.genre(),
                request.foodCategory(),
                request.priceCurrency(),
                request.minPrice(),
                request.maxPrice(),
                request.imageKeys(),
                toUpdateMenuCommands(request.menus()),
                request.hashtags(),
                request.curationTypes(),
                toUpdateBusinessHourCommands(request.businessHours()));
    }

    private List<MenuCommand> toMenuCommands(List<CreateRestaurantRequest.MenuRequest> menus) {
        if (menus == null) {
            return null;
        }
        return menus.stream()
                .map(menu -> new MenuCommand(menu.name(), menu.description(), menu.imageKey(),
                        menu.priceCurrency(), menu.priceAmount(), menu.main()))
                .toList();
    }

    private List<MenuCommand> toUpdateMenuCommands(List<UpdateRestaurantRequest.MenuRequest> menus) {
        if (menus == null) {
            return null;
        }
        return menus.stream()
                .map(menu -> new MenuCommand(menu.menuId(), menu.name(), menu.description(), menu.imageKey(),
                        menu.priceCurrency(), menu.priceAmount(), menu.main()))
                .toList();
    }

    private List<BusinessHourCommand> toBusinessHourCommands(
            List<CreateRestaurantRequest.BusinessHourRequest> businessHours) {
        if (businessHours == null) {
            return null;
        }
        return businessHours.stream()
                .map(hour -> new BusinessHourCommand(hour.dayOfWeek(), hour.openTime(),
                        hour.closeTime(), hour.breakStart(), hour.breakEnd(), hour.closed()))
                .toList();
    }

    private List<BusinessHourCommand> toUpdateBusinessHourCommands(
            List<UpdateRestaurantRequest.BusinessHourRequest> businessHours) {
        if (businessHours == null) {
            return null;
        }
        return businessHours.stream()
                .map(hour -> new BusinessHourCommand(hour.dayOfWeek(), hour.openTime(),
                        hour.closeTime(), hour.breakStart(), hour.breakEnd(), hour.closed()))
                .toList();
    }
}
