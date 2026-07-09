package org.sopt.hashi.restaurant.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.sopt.hashi.restaurant.code.RestaurantErrorCode;
import org.sopt.hashi.restaurant.domain.Restaurant;
import org.sopt.hashi.restaurant.domain.RestaurantBusinessHour;
import org.sopt.hashi.restaurant.domain.RestaurantCursor;
import org.sopt.hashi.restaurant.domain.RestaurantGenre;
import org.sopt.hashi.restaurant.domain.RestaurantListType;
import org.sopt.hashi.restaurant.domain.RestaurantMenu;
import org.sopt.hashi.restaurant.domain.RestaurantRepository;
import org.sopt.hashi.restaurant.domain.RestaurantSort;
import org.sopt.hashi.restaurant.domain.RestaurantSpecifications;
import org.sopt.hashi.restaurant.dto.RestaurantListResponse;
import org.sopt.hashi.restaurant.dto.RestaurantListResponse.RestaurantSummaryResponse;
import org.sopt.hashi.restaurant.dto.RestaurantMainResponse;
import org.sopt.hashi.restaurant.dto.RestaurantMenuListResponse;
import org.sopt.hashi.restaurant.dto.RestaurantMenuListResponse.RestaurantMenuResponse;
import org.sopt.hashi.restaurant.dto.RestaurantStoreInformationResponse;
import org.sopt.hashi.restaurant.dto.RestaurantStoreInformationResponse.BusinessHourResponse;
import org.sopt.hashi.restaurant.dto.RestaurantStoreInformationResponse.PriceRangeResponse;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.storage.FileStorage;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class RestaurantService {

    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 50;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    private final RestaurantRepository restaurantRepository;
    private final FileStorage fileStorage;

    public RestaurantService(RestaurantRepository restaurantRepository, FileStorage fileStorage) {
        this.restaurantRepository = restaurantRepository;
        this.fileStorage = fileStorage;
    }

    public RestaurantListResponse getRestaurants(
            String keyword,
            String genreValue,
            String sortValue,
            String typeValue,
            String cursor,
            Integer size
    ) {
        RestaurantGenre genre = parseGenre(genreValue);
        RestaurantSort sort = parseSort(sortValue);
        RestaurantListType type = parseListType(typeValue);
        RestaurantCursor decodedCursor = RestaurantCursorCodec.decode(cursor, sort);
        int pageSize = normalizeSize(size);

        Specification<Restaurant> specification = RestaurantSpecifications.active()
                .and(RestaurantSpecifications.cursorAfter(decodedCursor))
                .and(RestaurantSpecifications.genreEquals(genre))
                .and(RestaurantSpecifications.curationTypeEquals(type.curationType()))
                .and(RestaurantSpecifications.keywordContains(keyword));

        List<Restaurant> restaurants = restaurantRepository.findAll(
                specification,
                PageRequest.of(0, pageSize + 1, toSort(sort))
        ).getContent();

        boolean hasNext = restaurants.size() > pageSize;
        List<Restaurant> pageContent = hasNext
                ? new ArrayList<>(restaurants.subList(0, pageSize))
                : restaurants;
        String nextCursor = hasNext ? RestaurantCursorCodec.encode(pageContent.getLast(), sort) : null;

        return new RestaurantListResponse(
                pageContent.stream()
                        .map(this::toSummaryResponse)
                        .toList(),
                nextCursor,
                hasNext
        );
    }

    public RestaurantMainResponse getRestaurantSummary(Long restaurantId) {
        Restaurant restaurant = findActiveRestaurant(restaurantId);

        return new RestaurantMainResponse(
                restaurant.getId(),
                restaurant.getName(),
                restaurant.getLocalName(),
                restaurant.getRating(),
                restaurant.getReviewCount(),
                restaurant.getDescription(),
                restaurant.getAddress(),
                fileStorage.resolveFileUrl(restaurant.getThumbnailFileKey()),
                restaurant.getSavedCount(),
                restaurant.getReservationFee(),
                formatDate(restaurant.getAvailableDate()),
                formatTime(restaurant.getAvailableStartTime()),
                formatTime(restaurant.getAvailableEndTime())
        );
    }

    public RestaurantStoreInformationResponse getStoreInformation(Long restaurantId) {
        Restaurant restaurant = restaurantRepository.findActiveByIdWithBusinessHours(restaurantId)
                .orElseThrow(() -> new BusinessException(RestaurantErrorCode.NOT_FOUND));

        return new RestaurantStoreInformationResponse(
                restaurant.getId(),
                restaurant.getDescription(),
                restaurant.getBusinessHours().stream()
                        .sorted(Comparator.comparing(hour -> hour.getDayOfWeek().getValue()))
                        .map(this::toBusinessHourResponse)
                        .toList(),
                new PriceRangeResponse(
                        restaurant.getCurrency(),
                        toWholeAmount(restaurant.getMinPrice()),
                        toWholeAmount(restaurant.getMaxPrice())
                )
        );
    }

    public RestaurantMenuListResponse getRestaurantMenus(Long restaurantId, Long cursor, Integer size) {
        if (!restaurantRepository.existsByIdAndActiveTrue(restaurantId)) {
            throw new BusinessException(RestaurantErrorCode.NOT_FOUND);
        }

        int pageSize = normalizeSize(size);
        List<RestaurantMenu> menus = restaurantRepository.findMenusByRestaurantId(
                restaurantId,
                cursor,
                PageRequest.of(0, pageSize + 1)
        );

        boolean hasNext = menus.size() > pageSize;
        List<RestaurantMenu> pageContent = hasNext
                ? new ArrayList<>(menus.subList(0, pageSize))
                : menus;
        Long nextCursor = hasNext ? pageContent.getLast().getId() : null;

        return new RestaurantMenuListResponse(
                pageContent.stream()
                        .map(this::toMenuResponse)
                        .toList(),
                nextCursor,
                hasNext
        );
    }

    private RestaurantGenre parseGenre(String value) {
        if (value == null || value.isBlank() || "all".equals(value)) {
            return null;
        }
        return RestaurantGenre.from(value)
                .orElseThrow(() -> new BusinessException(RestaurantErrorCode.UNSUPPORTED_GENRE));
    }

    private RestaurantSort parseSort(String value) {
        if (value == null || value.isBlank()) {
            return RestaurantSort.BASIC;
        }
        return RestaurantSort.from(value)
                .orElseThrow(() -> new BusinessException(RestaurantErrorCode.UNSUPPORTED_SORT));
    }

    private RestaurantListType parseListType(String value) {
        if (value == null || value.isBlank()) {
            return RestaurantListType.ALL;
        }
        return RestaurantListType.from(value)
                .orElseThrow(() -> new BusinessException(RestaurantErrorCode.UNSUPPORTED_LIST_TYPE));
    }

    private int normalizeSize(Integer size) {
        if (size == null) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private Sort toSort(RestaurantSort sort) {
        return switch (sort) {
            case BASIC -> Sort.by(Sort.Order.desc("id"));
            case POPULAR -> Sort.by(Sort.Order.desc("popularityScore"), Sort.Order.desc("id"));
            case RATING -> Sort.by(Sort.Order.desc("rating"), Sort.Order.desc("id"));
        };
    }

    private Restaurant findActiveRestaurant(Long restaurantId) {
        return restaurantRepository.findByIdAndActiveTrue(restaurantId)
                .orElseThrow(() -> new BusinessException(RestaurantErrorCode.NOT_FOUND));
    }

    private RestaurantSummaryResponse toSummaryResponse(Restaurant restaurant) {
        return new RestaurantSummaryResponse(
                restaurant.getId(),
                restaurant.getName(),
                restaurant.getRating(),
                fileStorage.resolveFileUrl(restaurant.getThumbnailFileKey()),
                restaurant.getArea(),
                restaurant.getGenre().description(),
                restaurant.getDescription(),
                List.copyOf(restaurant.getTags()),
                restaurant.getAvailableDate(),
                restaurant.getAvailableStartTime(),
                restaurant.getAvailableEndTime()
        );
    }

    private BusinessHourResponse toBusinessHourResponse(RestaurantBusinessHour businessHour) {
        return new BusinessHourResponse(
                businessHour.getDayOfWeek().name(),
                formatTime(businessHour.getOpenTime()),
                formatTime(businessHour.getCloseTime()),
                formatTime(businessHour.getLastOrderTime()),
                businessHour.isClosed()
        );
    }

    private RestaurantMenuResponse toMenuResponse(RestaurantMenu menu) {
        return new RestaurantMenuResponse(
                menu.getId(),
                menu.getName(),
                menu.getDescription(),
                fileStorage.resolveFileUrl(menu.getImageFileKey()),
                menu.getCurrency(),
                toWholeAmount(menu.getPrice()),
                menu.isRepresentative()
        );
    }

    private String formatDate(LocalDate date) {
        return date == null ? null : date.toString();
    }

    private String formatTime(LocalTime time) {
        return time == null ? null : time.format(TIME_FORMATTER);
    }

    private Long toWholeAmount(BigDecimal amount) {
        return amount == null ? null : amount.longValue();
    }
}
