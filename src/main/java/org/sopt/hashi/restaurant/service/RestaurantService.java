package org.sopt.hashi.restaurant.service;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.sopt.hashi.restaurant.AdminRestaurantCommand;
import org.sopt.hashi.restaurant.AdminRestaurantCommand.BusinessHourCommand;
import org.sopt.hashi.restaurant.AdminRestaurantCommand.MenuCommand;
import org.sopt.hashi.restaurant.AdminRestaurantInfo;
import org.sopt.hashi.restaurant.AdminRestaurantInfo.AdminRestaurantBusinessHourInfo;
import org.sopt.hashi.restaurant.AdminRestaurantInfo.AdminRestaurantMenuInfo;
import org.sopt.hashi.restaurant.code.RestaurantErrorCode;
import org.sopt.hashi.restaurant.domain.Restaurant;
import org.sopt.hashi.restaurant.domain.RestaurantCurationType;
import org.sopt.hashi.restaurant.domain.RestaurantBusinessHour;
import org.sopt.hashi.restaurant.domain.RestaurantCursor;
import org.sopt.hashi.restaurant.domain.RestaurantGenre;
import org.sopt.hashi.restaurant.domain.RestaurantImage;
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
import org.sopt.hashi.restaurant.dto.RestaurantSearchKeywordRecommendationResponse;
import org.sopt.hashi.restaurant.dto.RestaurantSearchSuggestionResponse;
import org.sopt.hashi.restaurant.dto.RestaurantSearchSuggestionResponse.Suggestion;
import org.sopt.hashi.restaurant.dto.RestaurantStoreInformationResponse;
import org.sopt.hashi.restaurant.dto.RestaurantStoreInformationResponse.BusinessHourResponse;
import org.sopt.hashi.restaurant.dto.RestaurantStoreInformationResponse.PriceRangeResponse;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.shared.storage.FileStorage;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Transactional(readOnly = true)
public class RestaurantService {

    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 50;
    private static final int DEFAULT_SEARCH_SIZE = 10;
    private static final int MAX_SEARCH_SIZE = 50;
    private static final String SUGGESTION_TYPE_RESTAURANT = "restaurant";
    private static final String SUGGESTION_TYPE_MENU = "menu";
    private static final List<String> DEFAULT_RECOMMENDED_KEYWORDS = List.of(
            "스시",
            "라멘",
            "야키토리",
            "돈카츠",
            "규카츠",
            "오마카세",
            "소바",
            "우동",
            "텐동",
            "나베"
    );
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

    public RestaurantSearchSuggestionResponse getSearchSuggestions(String keyword, Integer size) {
        String normalizedKeyword = normalizeKeyword(keyword);
        int searchSize = normalizeSearchSize(size);

        List<Suggestion> suggestions = new ArrayList<>();
        restaurantRepository.findRestaurantSuggestionKeywords(
                        normalizedKeyword,
                        PageRequest.of(0, searchSize)
                ).stream()
                .map(value -> new Suggestion(value, SUGGESTION_TYPE_RESTAURANT))
                .forEach(suggestions::add);

        int remainingSize = searchSize - suggestions.size();
        if (remainingSize > 0) {
            restaurantRepository.findMenuSuggestionKeywords(
                            normalizedKeyword,
                            PageRequest.of(0, remainingSize)
                    ).stream()
                    .map(value -> new Suggestion(value, SUGGESTION_TYPE_MENU))
                    .forEach(suggestions::add);
        }

        return new RestaurantSearchSuggestionResponse(List.copyOf(suggestions));
    }

    public RestaurantSearchKeywordRecommendationResponse getSearchKeywordRecommendations(Integer size) {
        int searchSize = normalizeSearchSize(size);
        return new RestaurantSearchKeywordRecommendationResponse(
                DEFAULT_RECOMMENDED_KEYWORDS.stream()
                        .limit(searchSize)
                        .toList()
        );
    }

    public RestaurantMainResponse getRestaurantSummary(Long restaurantId) {
        Restaurant restaurant = restaurantRepository.findActiveByIdWithImages(restaurantId)
                .orElseThrow(() -> new BusinessException(RestaurantErrorCode.NOT_FOUND));

        return new RestaurantMainResponse(
                restaurant.getId(),
                restaurant.getName(),
                restaurant.getLocalName(),
                restaurant.getRating(),
                restaurant.getReviewCount(),
                restaurant.getDescription(),
                restaurant.getAddress(),
                fileStorage.resolveFileUrl(restaurant.getThumbnailFileKey()),
                toImageUrls(restaurant),
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
                toStoreDescription(restaurant),
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

    /** 어드민 식당 등록 — 필수 값 형식 검증은 admin 요청 DTO가, 도메인 값 해석·저장은 여기가 담당한다. */
    @Transactional
    public AdminRestaurantInfo createByAdmin(AdminRestaurantCommand command) {
        validateRequiredForCreate(command);

        Restaurant restaurant = Restaurant.create(
                command.name(),
                command.localName(),
                command.description(),
                command.storeDescription(),
                command.address(),
                command.area(),
                toGenre(command.genre()),
                command.thumbnailKey(),
                command.reservationFee(),
                command.currency(),
                command.minPrice(),
                command.maxPrice());
        restaurant.replaceImages(toImages(command.imageKeys()));
        restaurant.replaceMenus(toMenus(command.menus()));
        restaurant.replaceCurationTypes(toCurationTypes(command.curationTypes()));
        restaurant.replaceBusinessHours(toBusinessHours(command.businessHours()));
        validatePriceRange(restaurant);

        return toAdminInfo(restaurantRepository.save(restaurant));
    }

    /** 어드민 식당 수정 — 부분 수정(PATCH). null 필드는 유지하고, 컬렉션은 전체 교체한다. */
    @Transactional
    public AdminRestaurantInfo updateByAdmin(Long restaurantId, AdminRestaurantCommand command) {
        Restaurant restaurant = findRestaurantForAdmin(restaurantId);

        restaurant.updateBasicInfo(
                command.name(),
                command.localName(),
                command.description(),
                command.storeDescription(),
                command.address(),
                command.area(),
                command.genre() == null ? null : toGenre(command.genre()),
                command.thumbnailKey(),
                command.reservationFee(),
                command.currency(),
                command.minPrice(),
                command.maxPrice());
        validatePriceRange(restaurant);

        if (command.imageKeys() != null) {
            replaceImagesWithFlush(restaurant, command.imageKeys());
        }
        if (command.menus() != null) {
            restaurant.replaceMenus(toMenus(command.menus()));
        }
        if (command.curationTypes() != null) {
            restaurant.replaceCurationTypes(toCurationTypes(command.curationTypes()));
        }
        if (command.businessHours() != null) {
            replaceBusinessHoursWithFlush(restaurant, command.businessHours());
        }

        return toAdminInfo(restaurant);
    }

    /** 어드민 식당 삭제 — soft delete(active=false). 예약·리뷰가 참조하는 데이터는 보존한다. */
    @Transactional
    public void deleteByAdmin(Long restaurantId) {
        Restaurant restaurant = findRestaurantForAdmin(restaurantId);
        restaurant.deactivate();
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

    private String normalizeKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        return keyword.trim();
    }

    private int normalizeSearchSize(Integer size) {
        if (size == null) {
            return DEFAULT_SEARCH_SIZE;
        }
        if (size < 1) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        return Math.min(size, MAX_SEARCH_SIZE);
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

    private List<String> toImageUrls(Restaurant restaurant) {
        return restaurant.getImages().stream()
                .map(RestaurantImage::getFileKey)
                .map(fileStorage::resolveFileUrl)
                .toList();
    }

    private String toStoreDescription(Restaurant restaurant) {
        if (StringUtils.hasText(restaurant.getStoreDescription())) {
            return restaurant.getStoreDescription();
        }
        if (StringUtils.hasText(restaurant.getDescription())) {
            return restaurant.getDescription();
        }
        return "";
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

    private Restaurant findRestaurantForAdmin(Long restaurantId) {
        return restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new BusinessException(RestaurantErrorCode.NOT_FOUND));
    }

    private void validateRequiredForCreate(AdminRestaurantCommand command) {
        boolean missingRequired = command.name() == null || command.address() == null
                || command.genre() == null || command.reservationFee() == null || command.currency() == null;
        if (missingRequired) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
    }

    private void validatePriceRange(Restaurant restaurant) {
        BigDecimal minPrice = restaurant.getMinPrice();
        BigDecimal maxPrice = restaurant.getMaxPrice();
        boolean invalidRange = minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0;
        if (invalidRange) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
    }

    // (restaurant_id, display_order) 유니크 제약이 있어, Hibernate가 delete보다 insert를 먼저 실행하면
    // 같은 순서 값끼리 충돌한다. 기존 이미지를 비워 flush로 delete를 먼저 내보낸 뒤 새 이미지를 넣는다.
    private void replaceImagesWithFlush(Restaurant restaurant, List<String> imageKeys) {
        restaurant.replaceImages(List.of());
        restaurantRepository.flush();
        restaurant.replaceImages(toImages(imageKeys));
    }

    // (restaurant_id, day_of_week) 유니크 제약도 이미지와 동일한 insert-before-delete 충돌이 있어 같은 방식으로 교체한다.
    private void replaceBusinessHoursWithFlush(Restaurant restaurant, List<BusinessHourCommand> businessHours) {
        restaurant.replaceBusinessHours(List.of());
        restaurantRepository.flush();
        restaurant.replaceBusinessHours(toBusinessHours(businessHours));
    }

    private RestaurantGenre toGenre(String value) {
        return RestaurantGenre.from(value)
                .orElseThrow(() -> new BusinessException(RestaurantErrorCode.UNSUPPORTED_GENRE));
    }

    private List<RestaurantImage> toImages(List<String> imageKeys) {
        if (imageKeys == null) {
            return List.of();
        }
        return IntStream.range(0, imageKeys.size())
                .mapToObj(index -> RestaurantImage.create(imageKeys.get(index), index + 1))
                .toList();
    }

    private List<RestaurantMenu> toMenus(List<MenuCommand> menus) {
        if (menus == null) {
            return List.of();
        }
        return menus.stream()
                .map(menu -> RestaurantMenu.create(menu.name(), menu.description(), menu.imageKey(),
                        menu.currency(), menu.price(), menu.representative()))
                .toList();
    }

    private List<RestaurantBusinessHour> toBusinessHours(List<BusinessHourCommand> businessHours) {
        validateBusinessHourCoverage(businessHours);
        try {
            return businessHours.stream()
                    .map(hour -> RestaurantBusinessHour.create(hour.dayOfWeek(), hour.openTime(),
                            hour.closeTime(), hour.lastOrderTime(), hour.closed()))
                    .toList();
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(RestaurantErrorCode.INVALID_BUSINESS_HOURS, exception);
        }
    }

    // 요일별 유니크 제약(uk_restaurant_business_hour_day)과 매장정보 노출 스펙(요일 전체 응답)을 만족하려면
    // 7개 요일이 정확히 한 번씩 있어야 한다.
    private void validateBusinessHourCoverage(List<BusinessHourCommand> businessHours) {
        if (businessHours == null) {
            throw new BusinessException(RestaurantErrorCode.INVALID_BUSINESS_HOURS);
        }
        Set<DayOfWeek> days = businessHours.stream()
                .map(BusinessHourCommand::dayOfWeek)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        boolean coversAllDaysOnce = businessHours.size() == DayOfWeek.values().length
                && days.size() == DayOfWeek.values().length;
        if (!coversAllDaysOnce) {
            throw new BusinessException(RestaurantErrorCode.INVALID_BUSINESS_HOURS);
        }
    }

    private List<RestaurantCurationType> toCurationTypes(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .map(value -> RestaurantCurationType.from(value)
                        .orElseThrow(() -> new BusinessException(RestaurantErrorCode.UNSUPPORTED_CURATION_TYPE)))
                .toList();
    }

    private AdminRestaurantInfo toAdminInfo(Restaurant restaurant) {
        return new AdminRestaurantInfo(
                restaurant.getId(),
                restaurant.getName(),
                restaurant.getLocalName(),
                restaurant.getDescription(),
                restaurant.getStoreDescription(),
                restaurant.getAddress(),
                restaurant.getArea(),
                restaurant.getGenre().value(),
                fileStorage.resolveFileUrl(restaurant.getThumbnailFileKey()),
                restaurant.getReservationFee(),
                restaurant.getCurrency(),
                restaurant.getMinPrice(),
                restaurant.getMaxPrice(),
                restaurant.isActive(),
                restaurant.getImages().stream()
                        .map(RestaurantImage::getFileKey)
                        .map(fileStorage::resolveFileUrl)
                        .toList(),
                restaurant.getMenus().stream()
                        .map(this::toAdminMenuInfo)
                        .toList(),
                restaurant.getCurationTypes().stream()
                        .map(RestaurantCurationType::value)
                        .toList(),
                restaurant.getBusinessHours().stream()
                        .sorted(Comparator.comparing(hour -> hour.getDayOfWeek().getValue()))
                        .map(this::toAdminBusinessHourInfo)
                        .toList(),
                restaurant.getCreatedAt());
    }

    private AdminRestaurantBusinessHourInfo toAdminBusinessHourInfo(RestaurantBusinessHour businessHour) {
        return new AdminRestaurantBusinessHourInfo(
                businessHour.getDayOfWeek().name(),
                formatTime(businessHour.getOpenTime()),
                formatTime(businessHour.getCloseTime()),
                formatTime(businessHour.getLastOrderTime()),
                businessHour.isClosed());
    }

    private AdminRestaurantMenuInfo toAdminMenuInfo(RestaurantMenu menu) {
        return new AdminRestaurantMenuInfo(
                menu.getId(),
                menu.getName(),
                menu.getDescription(),
                fileStorage.resolveFileUrl(menu.getImageFileKey()),
                menu.getCurrency(),
                menu.getPrice(),
                menu.isRepresentative());
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
