package org.sopt.hashi.restaurant.service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.sopt.hashi.media.ImageReference;
import org.sopt.hashi.restaurant.AdminRestaurantCommand;
import org.sopt.hashi.restaurant.AdminRestaurantInfo;
import org.sopt.hashi.restaurant.RestaurantDetailInfo;
import org.sopt.hashi.restaurant.RestaurantInfo;
import org.sopt.hashi.restaurant.RestaurantPort;
import org.sopt.hashi.restaurant.RestaurantLocationInfo;
import org.sopt.hashi.restaurant.domain.Restaurant;
import org.sopt.hashi.restaurant.domain.RestaurantImage;
import org.sopt.hashi.restaurant.domain.RestaurantRepository;
import org.sopt.hashi.shared.storage.FileStorage;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link RestaurantPort} 구현 — 요약(id·name·address·대표 이미지)은 여기서 바로 조립하고,
 * 상세처럼 목록 API와 같은 계산(오늘 영업시간·메뉴 이미지·가격대)이 필요한 것은 {@link RestaurantService}에 위임한다.
 */
@Component
@Transactional(readOnly = true)
class RestaurantPortImpl implements RestaurantPort {

    private final RestaurantRepository restaurantRepository;
    private final RestaurantService restaurantService;
    private final FileStorage fileStorage;
    private final RestaurantLocationService locationService;

    RestaurantPortImpl(RestaurantRepository restaurantRepository, RestaurantService restaurantService,
                       FileStorage fileStorage, RestaurantLocationService locationService) {
        this.restaurantRepository = restaurantRepository;
        this.restaurantService = restaurantService;
        this.fileStorage = fileStorage;
        this.locationService = locationService;
    }

    @Override
    public boolean existsById(Long restaurantId) {
        return restaurantId != null && restaurantRepository.existsById(restaurantId);
    }

    @Override
    public Optional<RestaurantInfo> findSummaryById(Long restaurantId) {
        if (restaurantId == null) {
            return Optional.empty();
        }
        return restaurantRepository.findByIdWithImages(restaurantId)
                .map(this::toInfo);
    }

    @Override
    public List<RestaurantInfo> findSummaries(Collection<Long> restaurantIds) {
        if (restaurantIds == null || restaurantIds.isEmpty()) {
            return List.of();
        }

        List<Long> ids = restaurantIds.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.collectingAndThen(
                        Collectors.toCollection(LinkedHashSet::new),
                        List::copyOf
                ));
        if (ids.isEmpty()) {
            return List.of();
        }

        Map<Long, RestaurantInfo> summariesById = restaurantRepository.findAllByIdWithImages(ids).stream()
                .map(this::toInfo)
                .collect(Collectors.toMap(RestaurantInfo::id, Function.identity()));

        return ids.stream()
                .map(summariesById::get)
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public Optional<RestaurantDetailInfo> findDetailById(Long restaurantId) {
        return restaurantService.findDetailById(restaurantId);
    }

    @Override
    public List<RestaurantDetailInfo> findActiveDetails(Collection<Long> restaurantIds) {
        return restaurantService.findActiveDetails(restaurantIds);
    }

    @Override
    @Transactional
    public void increaseReviewStatistics(Long restaurantId, int rating) {
        if (restaurantRepository.increaseReviewStatistics(restaurantId, rating) == 0) {
            throw new IllegalStateException("리뷰 통계를 갱신할 식당을 찾을 수 없습니다.");
        }
    }

    @Override
    @Transactional
    public void decreaseReviewStatistics(Long restaurantId, int rating) {
        if (restaurantRepository.decreaseReviewStatistics(restaurantId, rating) == 0) {
            throw new IllegalStateException("차감할 식당 리뷰 통계가 올바르지 않습니다.");
        }
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AdminRestaurantInfo createByAdmin(AdminRestaurantCommand command) {
        return restaurantService.createByAdmin(command);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AdminRestaurantInfo updateByAdmin(Long restaurantId, AdminRestaurantCommand command) {
        return restaurantService.updateByAdmin(restaurantId, command);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deleteByAdmin(Long restaurantId) {
        restaurantService.deleteByAdmin(restaurantId);
    }

    @Override
    public RestaurantLocationInfo getLocationByAdmin(Long restaurantId) {
        return locationService.get(restaurantId);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public RestaurantLocationInfo retryLocationByAdmin(Long restaurantId, long expectedAddressRevision) {
        return locationService.retry(restaurantId, expectedAddressRevision);
    }

    private RestaurantInfo toInfo(Restaurant restaurant) {
        return new RestaurantInfo(
                restaurant.getId(),
                restaurant.getName(),
                restaurant.getAddress(),
                toThumbnailReference(restaurant)
        );
    }

    private ImageReference toThumbnailReference(Restaurant restaurant) {
        return restaurant.getThumbnailImage()
                .map(this::toImageReference)
                .orElse(null);
    }

    private ImageReference toImageReference(RestaurantImage image) {
        String legacyUrl = image.getFileKey() == null
                ? null
                : fileStorage.resolveFileUrl(image.getFileKey());
        return new ImageReference(image.getImageAssetId(), legacyUrl);
    }
}
