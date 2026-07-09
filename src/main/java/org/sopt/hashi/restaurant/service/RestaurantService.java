package org.sopt.hashi.restaurant.service;

import java.util.ArrayList;
import java.util.List;
import org.sopt.hashi.restaurant.code.RestaurantErrorCode;
import org.sopt.hashi.restaurant.domain.Restaurant;
import org.sopt.hashi.restaurant.domain.RestaurantCursor;
import org.sopt.hashi.restaurant.domain.RestaurantGenre;
import org.sopt.hashi.restaurant.domain.RestaurantListType;
import org.sopt.hashi.restaurant.domain.RestaurantRepository;
import org.sopt.hashi.restaurant.domain.RestaurantSort;
import org.sopt.hashi.restaurant.domain.RestaurantSpecifications;
import org.sopt.hashi.restaurant.dto.RestaurantListResponse;
import org.sopt.hashi.restaurant.dto.RestaurantListResponse.RestaurantSummaryResponse;
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
}
