package org.sopt.hashi.admin.service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.sopt.hashi.admin.dto.AdminRestaurantResponse;
import org.sopt.hashi.admin.dto.PreviewRestaurantLocationRequest;
import org.sopt.hashi.admin.dto.RestaurantLocationPreviewResponse;
import org.sopt.hashi.admin.dto.CreateRestaurantRequest;
import org.sopt.hashi.admin.dto.UpdateRestaurantRequest;
import org.sopt.hashi.admin.dto.RestaurantLocationResponse;
import org.sopt.hashi.admin.dto.RestaurantLocationReviewListResponse;
import org.sopt.hashi.admin.dto.RetryRestaurantLocationRequest;
import org.sopt.hashi.admin.dto.RestaurantPlacesSearchResponse;
import org.sopt.hashi.admin.dto.SearchRestaurantPlacesRequest;
import org.sopt.hashi.admin.dto.SelectRestaurantPlaceRequest;
import org.sopt.hashi.auth.AdminAccountPort;
import org.sopt.hashi.restaurant.AdminRestaurantCommand;
import org.sopt.hashi.restaurant.AdminRestaurantCommand.BusinessHourCommand;
import org.sopt.hashi.restaurant.AdminRestaurantCommand.ImageCommand;
import org.sopt.hashi.restaurant.AdminRestaurantCommand.MenuCommand;
import org.sopt.hashi.restaurant.RestaurantPort;
import org.sopt.hashi.restaurant.RestaurantLocationInfo;
import org.sopt.hashi.restaurant.RestaurantLocationReviewInfo;
import org.sopt.hashi.restaurant.RestaurantLocationReviewPage;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.springframework.stereotype.Service;

/**
 * 어드민 식당 관리 — 진입점 모듈이라 도메인 로직 없이 {@link RestaurantPort}로 위임하고
 * 요청→커맨드·응답 DTO 변환과 {@link AdminAccountPort}의 확인자 표시 정보를 조합한다(architecture.md §9).
 * 저장·검증·URL 변환은 restaurant 소관.
 */
@Service
public class AdminRestaurantService {

    private final RestaurantPort restaurantPort;
    private final AdminAccountPort adminAccountPort;

    public AdminRestaurantService(RestaurantPort restaurantPort, AdminAccountPort adminAccountPort) {
        this.restaurantPort = restaurantPort;
        this.adminAccountPort = adminAccountPort;
    }

    public AdminRestaurantResponse create(CreateRestaurantRequest request) {
        AdminRestaurantCommand command = toCommand(request);
        return AdminRestaurantResponse.from(request.locationSelectionToken() == null
                ? restaurantPort.createByAdmin(command)
                : restaurantPort.createByAdmin(command, request.locationSelectionToken()));
    }

    public RestaurantLocationPreviewResponse previewLocation(PreviewRestaurantLocationRequest request) {
        return RestaurantLocationPreviewResponse.from(restaurantPort.previewLocationByAdmin(request.toCommand()));
    }

    public AdminRestaurantResponse update(Long restaurantId, UpdateRestaurantRequest request) {
        return AdminRestaurantResponse.from(restaurantPort.updateByAdmin(restaurantId, toCommand(request)));
    }

    public void delete(Long restaurantId) {
        restaurantPort.deleteByAdmin(restaurantId);
    }

    public RestaurantLocationResponse getLocation(Long restaurantId) {
        return locationResponse(restaurantPort.getLocationByAdmin(restaurantId));
    }

    public RestaurantLocationResponse retryLocation(Long restaurantId, RetryRestaurantLocationRequest request) {
        return locationResponse(
                restaurantPort.retryLocationByAdmin(restaurantId, request.expectedAddressRevision()));
    }

    public RestaurantLocationReviewListResponse findLocationReviews(
            String status, String source, int page, int size) {
        if ((long) page * size > Integer.MAX_VALUE) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        RestaurantLocationReviewPage reviews = restaurantPort.findLocationReviewsByAdmin(status, source, page, size);
        Set<Long> adminIds = reviews.restaurants().stream()
                .map(RestaurantLocationReviewInfo::confirmedByAdminId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> loginIds = adminIds.isEmpty() ? Map.of() : adminAccountPort.findLoginIds(adminIds);
        return RestaurantLocationReviewListResponse.from(reviews, loginIds);
    }

    public RestaurantPlacesSearchResponse searchLocationPlaces(
            Long restaurantId, SearchRestaurantPlacesRequest request) {
        return RestaurantPlacesSearchResponse.from(restaurantPort.searchLocationPlacesByAdmin(
                restaurantId, request.expectedAddressRevision()));
    }

    public RestaurantLocationResponse selectLocationPlace(
            Long restaurantId, SelectRestaurantPlaceRequest request) {
        return locationResponse(restaurantPort.selectLocationPlaceByAdmin(
                restaurantId, request.expectedAddressRevision(), request.selectionToken()));
    }

    private RestaurantLocationResponse locationResponse(RestaurantLocationInfo info) {
        Long adminId = info.confirmedByAdminId();
        String loginId = adminId == null ? null : adminAccountPort.findLoginIds(Set.of(adminId)).get(adminId);
        return RestaurantLocationResponse.from(info, loginId);
    }

    private AdminRestaurantCommand toCommand(CreateRestaurantRequest request) {
        return new AdminRestaurantCommand(
                request.name(),
                request.localName(),
                request.summary(),
                request.description(),
                request.address(),
                request.geocodingAddress(),
                request.area(),
                request.genre(),
                request.foodCategory(),
                request.placeType(),
                request.priceCurrency(),
                request.minPrice(),
                request.maxPrice(),
                request.imageKeys(),
                request.imageAssetIds(),
                null,
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
                request.geocodingAddress(),
                request.area(),
                request.genre(),
                request.foodCategory(),
                request.placeType(),
                request.priceCurrency(),
                request.minPrice(),
                request.maxPrice(),
                request.imageKeys(),
                null,
                toImageCommands(request.images()),
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
                .map(menu -> new MenuCommand(null, menu.name(), menu.description(), menu.imageKey(),
                        menu.imageAssetId(),
                        menu.priceCurrency(), menu.priceAmount(), menu.main()))
                .toList();
    }

    private List<MenuCommand> toUpdateMenuCommands(List<UpdateRestaurantRequest.MenuRequest> menus) {
        if (menus == null) {
            return null;
        }
        return menus.stream()
                .map(menu -> new MenuCommand(
                        menu.menuId(), menu.name(), menu.description(), menu.imageKey(),
                        menu.imageAssetId(),
                        menu.priceCurrency(), menu.priceAmount(), menu.main()))
                .toList();
    }

    private List<ImageCommand> toImageCommands(List<UpdateRestaurantRequest.ImageRequest> images) {
        if (images == null) {
            return null;
        }
        return images.stream()
                .map(image -> new ImageCommand(
                        image.restaurantImageId(), image.imageAssetId()))
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
