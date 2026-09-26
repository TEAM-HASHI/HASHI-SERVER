package org.sopt.hashi.restaurant.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.extern.slf4j.Slf4j;
import org.sopt.hashi.media.ImageReference;
import org.sopt.hashi.media.MediaAssetPurpose;
import org.sopt.hashi.media.MediaAssetUse;
import org.sopt.hashi.media.MediaImage;
import org.sopt.hashi.media.MediaImageRequest;
import org.sopt.hashi.media.MediaImageRole;
import org.sopt.hashi.media.MediaImageSelection;
import org.sopt.hashi.media.MediaPort;
import org.sopt.hashi.restaurant.AdminRestaurantCommand;
import org.sopt.hashi.restaurant.RestaurantMapInfo;
import org.sopt.hashi.restaurant.domain.RestaurantMapCandidate;
import org.sopt.hashi.restaurant.dto.RestaurantMapPageResponse.MapCardResponse;
import org.sopt.hashi.restaurant.AdminRestaurantCommand.BusinessHourCommand;
import org.sopt.hashi.restaurant.AdminRestaurantCommand.ImageCommand;
import org.sopt.hashi.restaurant.AdminRestaurantCommand.MenuCommand;
import org.sopt.hashi.restaurant.AdminRestaurantInfo;
import org.sopt.hashi.restaurant.AdminRestaurantInfo.AdminRestaurantBusinessHourInfo;
import org.sopt.hashi.restaurant.AdminRestaurantInfo.AdminRestaurantMenuInfo;
import org.sopt.hashi.restaurant.RestaurantDetailInfo;
import org.sopt.hashi.restaurant.RestaurantDetailInfo.PriceRangeInfo;
import org.sopt.hashi.restaurant.RestaurantDetailInfo.TodayBusinessHourInfo;
import org.sopt.hashi.restaurant.RestaurantImageInfo;
import org.sopt.hashi.restaurant.code.RestaurantErrorCode;
import org.sopt.hashi.restaurant.domain.Restaurant;
import org.sopt.hashi.restaurant.domain.RestaurantCurationType;
import org.sopt.hashi.restaurant.domain.RestaurantBusinessHour;
import org.sopt.hashi.restaurant.domain.RestaurantCursor;
import org.sopt.hashi.restaurant.domain.RestaurantGenre;
import org.sopt.hashi.restaurant.domain.RestaurantPlaceType;
import org.sopt.hashi.restaurant.domain.RestaurantImage;
import org.sopt.hashi.restaurant.domain.RestaurantListType;
import org.sopt.hashi.restaurant.domain.RestaurantMenu;
import org.sopt.hashi.restaurant.domain.RestaurantRepository;
import org.sopt.hashi.restaurant.domain.RestaurantReservationPolicy;
import org.sopt.hashi.restaurant.domain.RestaurantSort;
import org.sopt.hashi.restaurant.domain.RestaurantSpecifications;
import org.sopt.hashi.restaurant.domain.PriceCurrency;
import org.sopt.hashi.restaurant.dto.RestaurantListResponse;
import org.sopt.hashi.restaurant.dto.RestaurantListResponse.RestaurantSummaryResponse;
import org.sopt.hashi.restaurant.dto.RestaurantListResponse.TodayBusinessHourResponse;
import org.sopt.hashi.restaurant.dto.RestaurantMainResponse;
import org.sopt.hashi.restaurant.dto.RestaurantMenuDetailResponse;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
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
    private final MediaPort mediaPort;
    private final Clock japanClock;

    public RestaurantService(
            RestaurantRepository restaurantRepository,
            FileStorage fileStorage,
            MediaPort mediaPort,
            @Qualifier("japanClock") Clock japanClock
    ) {
        this.restaurantRepository = restaurantRepository;
        this.fileStorage = fileStorage;
        this.mediaPort = mediaPort;
        this.japanClock = japanClock;
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

        Specification<Restaurant> specification = RestaurantSpecifications.notDeleted()
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
        LocalDate businessDate = LocalDate.now(japanClock);
        Map<Long, RestaurantBusinessHour> businessHours = findBusinessHours(
                pageContent,
                businessDate.getDayOfWeek()
        );
        MediaProjection mediaProjection = loadRestaurantProjection(
                pageContent, MediaImageRole.RESTAURANT_CARD, 3);

        return new RestaurantListResponse(
                pageContent.stream()
                        .map(restaurant -> toSummaryResponse(
                                restaurant,
                                businessDate,
                                businessHours.get(restaurant.getId()),
                                mediaProjection
                        ))
                        .toList(),
                nextCursor,
                hasNext
        );
    }

    /** 지도 페이지도 기존 카드 변환을 공유하되 순위 값은 최초 세션 값을 사용한다. */
    public List<MapCardResponse> findMapCards(List<RestaurantMapCandidate> candidates,
                                            Map<Long, RestaurantMapInfo> mapInfos) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<Restaurant> restaurants = restaurantRepository.findAllByIdInAndDeletedFalse(
                candidates.stream().map(RestaurantMapCandidate::restaurantId).toList());
        var byId = restaurants.stream().collect(Collectors.toMap(Restaurant::getId, Function.identity()));
        LocalDate businessDate = LocalDate.now(japanClock);
        var hours = findBusinessHours(restaurants, businessDate.getDayOfWeek());
        var media = loadRestaurantProjection(restaurants, MediaImageRole.RESTAURANT_CARD, 3);
        return candidates.stream().map(candidate -> {
            Restaurant restaurant = byId.get(candidate.restaurantId());
            return MapCardResponse.from(toSummaryResponse(restaurant, businessDate, hours.get(restaurant.getId()), media),
                    candidate, restaurant.getPlaceType().value(), new PriceRangeResponse(
                            restaurant.getPriceCurrency().value(), toWholeAmount(restaurant.getMinPrice()),
                            toWholeAmount(restaurant.getMaxPrice())), mapInfos.get(restaurant.getId()).location());
        }).toList();
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

    public RestaurantMainResponse getRandomRestaurantRecommendation(Long excludeRestaurantId) {
        Long restaurantId = restaurantRepository.findRandomRestaurantIdExcluding(excludeRestaurantId)
                .orElseThrow(() -> new BusinessException(RestaurantErrorCode.RECOMMENDATION_NOT_FOUND));

        return getRestaurantSummary(restaurantId);
    }

    public RestaurantMainResponse getRestaurantSummary(Long restaurantId) {
        Restaurant restaurant = restaurantRepository.findActiveByIdWithImages(restaurantId)
                .orElseThrow(() -> new BusinessException(RestaurantErrorCode.NOT_FOUND));
        MediaProjection mediaProjection = loadRestaurantProjection(
                List.of(restaurant), MediaImageRole.RESTAURANT_HERO, Integer.MAX_VALUE);
        List<RestaurantImage> orderedImages = orderedImages(restaurant, Integer.MAX_VALUE);
        ProjectedImage thumbnail = orderedImages.isEmpty()
                ? ProjectedImage.empty()
                : projectImage(
                        orderedImages.getFirst(),
                        MediaImageRole.RESTAURANT_THUMBNAIL,
                        mediaProjection);

        return new RestaurantMainResponse(
                restaurant.getId(),
                restaurant.getName(),
                restaurant.getLocalName(),
                restaurant.getRating(),
                restaurant.getReviewCount(),
                restaurant.getSummary(),
                restaurant.getFoodCategory(),
                restaurant.getAddress(),
                thumbnail.url(),
                toImageInfo(orderedImages.isEmpty() ? null : orderedImages.getFirst(), thumbnail),
                toImageUrls(orderedImages, MediaImageRole.RESTAURANT_HERO, mediaProjection),
                toImageInfos(orderedImages, MediaImageRole.RESTAURANT_HERO, mediaProjection),
                RestaurantReservationPolicy.RESERVATION_FEE
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
                        restaurant.getPriceCurrency().value(),
                        toWholeAmount(restaurant.getMinPrice()),
                        toWholeAmount(restaurant.getMaxPrice())
                )
        );
    }

    public RestaurantMenuListResponse getRestaurantMenus(
            Long restaurantId,
            Long excludeMenuId,
            Long cursor,
            Integer size
    ) {
        if (!restaurantRepository.existsByIdAndDeletedFalse(restaurantId)) {
            throw new BusinessException(RestaurantErrorCode.NOT_FOUND);
        }

        int pageSize = normalizeSize(size);
        RestaurantMenu cursorMenu = resolveMenuCursor(restaurantId, cursor);
        List<RestaurantMenu> menus = restaurantRepository.findMenusByRestaurantId(
                restaurantId,
                excludeMenuId,
                cursorMenu == null ? null : cursorMenu.isMain(),
                cursorMenu == null ? null : cursorMenu.getName(),
                cursorMenu == null ? null : cursorMenu.getId(),
                PageRequest.of(0, pageSize + 1)
        );

        boolean hasNext = menus.size() > pageSize;
        List<RestaurantMenu> pageContent = hasNext
                ? new ArrayList<>(menus.subList(0, pageSize))
                : menus;
        Long nextCursor = hasNext ? pageContent.getLast().getId() : null;
        MediaProjection mediaProjection = loadMenuProjection(
                pageContent, MediaImageRole.MENU_LIST);

        return new RestaurantMenuListResponse(
                pageContent.stream()
                        .map(menu -> toMenuResponse(menu, mediaProjection))
                        .toList(),
                nextCursor,
                hasNext
        );
    }

    public RestaurantMenuDetailResponse getRestaurantMenu(Long restaurantId, Long menuId) {
        RestaurantMenu menu = restaurantRepository.findMenuByRestaurantIdAndMenuId(restaurantId, menuId)
                .orElseThrow(() -> menuNotFoundException(restaurantId));
        long otherMenuCount = restaurantRepository.countOtherMenusByRestaurantId(restaurantId, menuId);
        MediaProjection mediaProjection = loadMenuProjection(
                List.of(menu), MediaImageRole.MENU_DETAIL);
        ProjectedImage menuImage = projectMenuImage(
                menu, MediaImageRole.MENU_DETAIL, mediaProjection);

        return new RestaurantMenuDetailResponse(
                menu.getId(),
                menu.getName(),
                menu.getDescription(),
                menuImage.url(),
                menuImage.image(),
                menu.getPriceCurrency() == null ? null : menu.getPriceCurrency().value(),
                toWholeAmount(menu.getPriceAmount()),
                menu.isMain(),
                otherMenuCount
        );
    }

    /**
     * 식당 상세(모듈 간 계약 {@link RestaurantDetailInfo}) 단건 — 삭제된 식당도 돌려준다(예약 상세 표시용).
     * 목록 카드와 같은 항목(평점·리뷰 수·메뉴 이미지·오늘 영업시간·가격대)을 같은 계산으로 채운다.
     */
    public Optional<RestaurantDetailInfo> findDetailById(Long restaurantId) {
        if (restaurantId == null) {
            return Optional.empty();
        }
        return restaurantRepository.findByIdWithImages(restaurantId)
                .map(restaurant -> toDetailInfos(List.of(restaurant)).getFirst());
    }

    /** 사용자 노출용 식당 상세 목록 — 삭제된 식당은 제외하고 요청 순서를 유지한다(매거진 연결 식당 등). */
    public List<RestaurantDetailInfo> findActiveDetails(Collection<Long> restaurantIds) {
        List<Long> ids = distinctIds(restaurantIds);
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Restaurant> restaurants = restaurantRepository.findAllByIdInAndDeletedFalse(ids);
        Map<Long, RestaurantDetailInfo> detailsById = toDetailInfos(restaurants).stream()
                .collect(Collectors.toMap(RestaurantDetailInfo::id, Function.identity()));
        return ids.stream()
                .map(detailsById::get)
                .filter(Objects::nonNull)
                .toList();
    }

    /** 어드민 식당 등록 — 필수 값 형식 검증은 admin 요청 DTO가, 도메인 값 해석·저장은 여기가 담당한다. */
    @Transactional
    public AdminRestaurantInfo createByAdmin(AdminRestaurantCommand command) {
        validateRequiredForCreate(command);

        List<RestaurantImage> images = toImagesForCreate(command);
        List<RestaurantMenu> menus = toMenus(command.menus());
        List<RestaurantBusinessHour> businessHours = toBusinessHours(command.businessHours());
        List<RestaurantCurationType> curationTypes = toCurationTypes(command.curationTypes());
        List<MediaAssetUse> claims = new ArrayList<>();
        images.stream()
                .map(RestaurantImage::getImageAssetId)
                .filter(Objects::nonNull)
                .map(assetId -> new MediaAssetUse(assetId, MediaAssetPurpose.RESTAURANT))
                .forEach(claims::add);
        menus.stream()
                .map(RestaurantMenu::getImageAssetId)
                .filter(Objects::nonNull)
                .map(assetId -> new MediaAssetUse(assetId, MediaAssetPurpose.RESTAURANT_MENU))
                .forEach(claims::add);

        Restaurant restaurant = Restaurant.create(
                command.name(),
                command.localName(),
                command.summary(),
                command.description(),
                command.address(),
                command.area(),
                toGenre(command.genre()),
                command.foodCategory(),
                toPlaceType(command.placeType()),
                toPriceCurrency(command.priceCurrency()),
                command.minPrice(),
                command.maxPrice());
        restaurant.replaceImages(images);
        restaurant.replaceMenus(menus);
        restaurant.replaceHashtags(command.hashtags());
        restaurant.replaceCurationTypes(curationTypes);
        restaurant.replaceBusinessHours(businessHours);
        validatePriceRange(restaurant);

        reconcileMediaBindings(claims, List.of());
        Restaurant saved = restaurantRepository.save(restaurant);
        restaurantRepository.flush();
        // 생성된 id는 응답 body에만 있어 로그로 남겨야 추적 가능하다 (adminId는 MDC)
        log.info("어드민 식당 등록. restaurantId={}", saved.getId());
        return toAdminInfo(saved);
    }

    /** 어드민 식당 수정 — 부분 수정(PATCH). null 필드는 유지하고, 컬렉션은 전체 교체한다. */
    @Transactional
    public AdminRestaurantInfo updateByAdmin(Long restaurantId, AdminRestaurantCommand command) {
        validateImageFieldsForUpdate(command);
        validateNonBlankIfPresent(command.localName());
        // 자유 텍스트 전환(#145) 후에도 값 비우기 불가 정책 유지 — enum 시절엔 빈 값이 변환 단계에서 거부됐다
        validateNonBlankIfPresent(command.foodCategory());
        validateNonBlankIfPresent(command.placeType());
        validateNonEmptyIfPresent(command.imageKeys());
        validateNonEmptyIfPresent(command.images());
        validateNonEmptyIfPresent(command.hashtags());
        validateExclusiveImageCollections(command.imageKeys(), command.images());
        Restaurant restaurant = findRestaurantForAdminUpdate(restaurantId);

        RestaurantGenre genre = command.genre() == null ? null : toGenre(command.genre());
        RestaurantPlaceType placeType = command.placeType() == null ? null : toPlaceType(command.placeType());
        PriceCurrency priceCurrency = command.priceCurrency() == null
                ? null
                : toPriceCurrency(command.priceCurrency());
        BigDecimal nextMinPrice = command.minPrice() == null
                ? restaurant.getMinPrice()
                : command.minPrice();
        BigDecimal nextMaxPrice = command.maxPrice() == null
                ? restaurant.getMaxPrice()
                : command.maxPrice();
        validatePriceRange(nextMinPrice, nextMaxPrice);
        List<RestaurantCurationType> curationTypes = command.curationTypes() == null
                ? null
                : toCurationTypes(command.curationTypes());
        List<RestaurantBusinessHour> businessHours = command.businessHours() == null
                ? null
                : toBusinessHours(command.businessHours());
        RestaurantImageUpdatePlan imagePlan = planImageUpdate(restaurant, command);
        RestaurantMenuUpdatePlan menuPlan = planMenuUpdate(restaurant, command.menus());

        List<MediaAssetUse> claims = new ArrayList<>(imagePlan.claims());
        claims.addAll(menuPlan.claims());
        List<MediaAssetUse> retires = new ArrayList<>(imagePlan.retires());
        retires.addAll(menuPlan.retires());
        reconcileMediaBindings(claims, retires);

        restaurant.updateBasicInfo(
                command.name(),
                command.localName(),
                command.summary(),
                command.description(),
                command.address(),
                command.area(),
                genre,
                command.foodCategory(),
                placeType,
                priceCurrency,
                command.minPrice(),
                command.maxPrice());

        applyImageUpdate(restaurant, imagePlan);
        applyMenuUpdate(restaurant, menuPlan);
        if (command.hashtags() != null) {
            restaurant.replaceHashtags(command.hashtags());
        }
        if (command.curationTypes() != null) {
            restaurant.replaceCurationTypes(curationTypes);
        }
        if (businessHours != null) {
            replaceBusinessHoursWithFlush(restaurant, businessHours);
        }

        restaurantRepository.flush();
        return toAdminInfo(restaurant);
    }

    /** 어드민 식당 삭제 — soft delete(deleted=true). 예약·리뷰가 참조하는 데이터는 보존한다. */
    @Transactional
    public void deleteByAdmin(Long restaurantId) {
        Restaurant restaurant = findRestaurantForAdminUpdate(restaurantId);
        restaurant.softDelete();
        // 노출 종료 전이 — 예약·리뷰가 계속 참조하므로 언제 내려갔는지 기록이 필요하다 (adminId는 MDC)
        log.info("어드민 식당 삭제(soft). restaurantId={}", restaurantId);
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

    private RestaurantMenu resolveMenuCursor(Long restaurantId, Long cursor) {
        if (cursor == null) {
            return null;
        }
        return restaurantRepository.findMenuByRestaurantIdAndMenuId(restaurantId, cursor)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.INVALID_INPUT));
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
            case POPULAR -> Sort.by(
                    Sort.Order.desc("reviewCount"),
                    Sort.Order.desc("rating"),
                    Sort.Order.desc("id"));
            case RATING -> Sort.by(Sort.Order.desc("rating"), Sort.Order.desc("id"));
        };
    }

    private Map<Long, RestaurantBusinessHour> findBusinessHours(
            List<Restaurant> restaurants,
            DayOfWeek dayOfWeek
    ) {
        if (restaurants.isEmpty()) {
            return Map.of();
        }

        List<Long> restaurantIds = restaurants.stream()
                .map(Restaurant::getId)
                .toList();
        return restaurantRepository.findBusinessHoursByRestaurantIdsAndDayOfWeek(restaurantIds, dayOfWeek).stream()
                .collect(Collectors.toMap(
                        businessHour -> businessHour.getRestaurant().getId(),
                        Function.identity()
                ));
    }

    private RestaurantSummaryResponse toSummaryResponse(
            Restaurant restaurant,
            LocalDate businessDate,
            RestaurantBusinessHour businessHour,
            MediaProjection mediaProjection
    ) {
        List<RestaurantImage> orderedImages = orderedImages(restaurant, 3);
        ProjectedImage thumbnail = orderedImages.isEmpty()
                ? ProjectedImage.empty()
                : projectImage(
                        orderedImages.getFirst(),
                        MediaImageRole.RESTAURANT_THUMBNAIL,
                        mediaProjection);

        return new RestaurantSummaryResponse(
                restaurant.getId(),
                restaurant.getName(),
                restaurant.getRating(),
                thumbnail.url(),
                toImageInfo(orderedImages.isEmpty() ? null : orderedImages.getFirst(), thumbnail),
                toImageUrls(orderedImages, MediaImageRole.RESTAURANT_CARD, mediaProjection),
                toImageInfos(orderedImages, MediaImageRole.RESTAURANT_CARD, mediaProjection),
                restaurant.getArea(),
                restaurant.getGenre().description(),
                restaurant.getFoodCategory(),
                restaurant.getSummary(),
                List.copyOf(restaurant.getHashtags()),
                toTodayBusinessHourResponse(businessDate, businessHour)
        );
    }

    private TodayBusinessHourResponse toTodayBusinessHourResponse(
            LocalDate businessDate,
            RestaurantBusinessHour businessHour
    ) {
        if (businessHour == null) {
            return null;
        }

        return new TodayBusinessHourResponse(
                businessDate.toString(),
                businessHour.getDayOfWeek().name(),
                formatTime(businessHour.getOpenTime()),
                formatTime(businessHour.getCloseTime()),
                businessHour.isClosed()
        );
    }

    private MediaProjection loadRestaurantProjection(
            List<Restaurant> restaurants,
            MediaImageRole collectionRole,
            int imageLimit
    ) {
        List<MediaImageRequest> requests = new ArrayList<>();
        for (Restaurant restaurant : restaurants) {
            List<RestaurantImage> images = orderedImages(restaurant, imageLimit);
            images.stream()
                    .map(RestaurantImage::getImageAssetId)
                    .filter(Objects::nonNull)
                    .map(assetId -> new MediaImageRequest(assetId, collectionRole))
                    .forEach(requests::add);
            if (!images.isEmpty() && images.getFirst().getImageAssetId() != null) {
                requests.add(new MediaImageRequest(
                        images.getFirst().getImageAssetId(),
                        MediaImageRole.RESTAURANT_THUMBNAIL));
            }
        }
        return loadProjection(requests);
    }

    private MediaProjection loadMenuProjection(
            List<RestaurantMenu> menus,
            MediaImageRole role
    ) {
        return loadProjection(menus.stream()
                .map(RestaurantMenu::getImageAssetId)
                .filter(Objects::nonNull)
                .map(assetId -> new MediaImageRequest(assetId, role))
                .toList());
    }

    private MediaProjection loadAdminProjection(
            List<RestaurantImage> images,
            List<RestaurantMenu> menus
    ) {
        List<MediaImageRequest> requests = new ArrayList<>();
        images.stream()
                .map(RestaurantImage::getImageAssetId)
                .filter(Objects::nonNull)
                .map(assetId -> new MediaImageRequest(assetId, MediaImageRole.RESTAURANT_HERO))
                .forEach(requests::add);
        if (!images.isEmpty() && images.getFirst().getImageAssetId() != null) {
            requests.add(new MediaImageRequest(
                    images.getFirst().getImageAssetId(),
                    MediaImageRole.RESTAURANT_THUMBNAIL));
        }
        menus.stream()
                .map(RestaurantMenu::getImageAssetId)
                .filter(Objects::nonNull)
                .map(assetId -> new MediaImageRequest(assetId, MediaImageRole.MENU_LIST))
                .forEach(requests::add);
        return loadProjection(requests);
    }

    private MediaProjection loadProjection(List<MediaImageRequest> requests) {
        List<MediaImageRequest> distinctRequests = requests.stream().distinct().toList();
        if (distinctRequests.isEmpty()) {
            return MediaProjection.empty();
        }
        return new MediaProjection(mediaPort.findImages(distinctRequests));
    }

    private List<RestaurantImage> orderedImages(Restaurant restaurant, int limit) {
        return restaurant.getImages().stream()
                .sorted(Comparator.comparingInt(RestaurantImage::getDisplayOrder))
                .limit(limit)
                .toList();
    }

    private List<String> toImageUrls(
            List<RestaurantImage> images,
            MediaImageRole role,
            MediaProjection mediaProjection
    ) {
        return images.stream()
                .map(image -> projectImage(image, role, mediaProjection).url())
                .filter(Objects::nonNull)
                .toList();
    }

    private List<RestaurantImageInfo> toImageInfos(
            List<RestaurantImage> images,
            MediaImageRole role,
            MediaProjection mediaProjection
    ) {
        return images.stream()
                .map(image -> toImageInfo(image, projectImage(image, role, mediaProjection)))
                .toList();
    }

    private RestaurantImageInfo toImageInfo(RestaurantImage image, ProjectedImage projectedImage) {
        if (image == null) {
            return null;
        }
        return new RestaurantImageInfo(
                image.getId(), image.getDisplayOrder(), projectedImage.image(),
                image.getImageAssetId() == null ? projectedImage.url() : null);
    }

    private ProjectedImage projectImage(
            RestaurantImage image,
            MediaImageRole role,
            MediaProjection mediaProjection
    ) {
        if (image.getImageAssetId() == null) {
            MediaImageSelection selection = MediaImageSelection.from(
                    ImageReference.legacy(resolveLegacyUrl(image.getFileKey())), null);
            return new ProjectedImage(selection.url(), selection.image());
        }
        MediaImage mediaImage = mediaProjection.find(image.getImageAssetId(), role);
        MediaImageSelection selection = MediaImageSelection.from(
                ImageReference.asset(image.getImageAssetId()), mediaImage);
        return new ProjectedImage(selection.url(), selection.image());
    }

    private ProjectedImage projectMenuImage(
            RestaurantMenu menu,
            MediaImageRole role,
            MediaProjection mediaProjection
    ) {
        if (menu.getImageAssetId() == null) {
            MediaImageSelection selection = MediaImageSelection.from(
                    ImageReference.legacy(resolveLegacyUrl(menu.getImageKey())), null);
            return new ProjectedImage(selection.url(), selection.image());
        }
        MediaImage mediaImage = mediaProjection.find(menu.getImageAssetId(), role);
        MediaImageSelection selection = MediaImageSelection.from(
                ImageReference.asset(menu.getImageAssetId()), mediaImage);
        return new ProjectedImage(selection.url(), selection.image());
    }

    private String resolveLegacyUrl(String fileKey) {
        return fileKey == null ? null : fileStorage.resolveFileUrl(fileKey);
    }

    private BusinessHourResponse toBusinessHourResponse(RestaurantBusinessHour businessHour) {
        return new BusinessHourResponse(
                businessHour.getDayOfWeek().name(),
                formatTime(businessHour.getOpenTime()),
                formatTime(businessHour.getCloseTime()),
                formatTime(businessHour.getBreakStart()),
                formatTime(businessHour.getBreakEnd()),
                businessHour.isClosed()
        );
    }

    private RestaurantMenuResponse toMenuResponse(
            RestaurantMenu menu,
            MediaProjection mediaProjection
    ) {
        ProjectedImage menuImage = projectMenuImage(
                menu, MediaImageRole.MENU_LIST, mediaProjection);
        return new RestaurantMenuResponse(
                menu.getId(),
                menu.getName(),
                menu.getDescription(),
                menuImage.url(),
                menuImage.image(),
                menu.getPriceCurrency() == null ? null : menu.getPriceCurrency().value(),
                toWholeAmount(menu.getPriceAmount()),
                menu.isMain()
        );
    }

    private BusinessException menuNotFoundException(Long restaurantId) {
        if (!restaurantRepository.existsByIdAndDeletedFalse(restaurantId)) {
            return new BusinessException(RestaurantErrorCode.NOT_FOUND);
        }
        return new BusinessException(RestaurantErrorCode.MENU_NOT_FOUND);
    }

    private Restaurant findRestaurantForAdminUpdate(Long restaurantId) {
        return restaurantRepository.findByIdForUpdate(restaurantId)
                .orElseThrow(() -> new BusinessException(RestaurantErrorCode.NOT_FOUND));
    }

    private void validateRequiredForCreate(AdminRestaurantCommand command) {
        boolean missingRequired = command.name() == null || command.localName() == null || command.localName().isBlank()
                || command.address() == null
                || command.summary() == null || command.description() == null
                || command.area() == null || command.genre() == null
                || command.foodCategory() == null || command.foodCategory().isBlank()
                || command.placeType() == null || command.placeType().isBlank()
                || command.priceCurrency() == null || command.minPrice() == null || command.maxPrice() == null
                || command.images() != null
                || !hasExactlyOneCreateImageSource(command.imageKeys(), command.imageAssetIds())
                || command.hashtags() == null || command.hashtags().isEmpty();
        if (missingRequired) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
    }

    private void validateNonEmptyIfPresent(List<?> values) {
        if (values != null && values.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
    }

    private void validateNonBlankIfPresent(String value) {
        if (value != null && value.isBlank()) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
    }

    private void validatePriceRange(Restaurant restaurant) {
        validatePriceRange(restaurant.getMinPrice(), restaurant.getMaxPrice());
    }

    private void validatePriceRange(BigDecimal minPrice, BigDecimal maxPrice) {
        boolean invalidRange = minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0;
        if (invalidRange) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
    }

    private void validateImageFieldsForUpdate(AdminRestaurantCommand command) {
        if (command.imageAssetIds() != null) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
    }

    private boolean hasExactlyOneCreateImageSource(
            List<String> imageKeys,
            List<UUID> imageAssetIds
    ) {
        boolean legacyProvided = imageKeys != null;
        boolean assetsProvided = imageAssetIds != null;
        if (legacyProvided == assetsProvided) {
            return false;
        }
        return legacyProvided ? !imageKeys.isEmpty() : !imageAssetIds.isEmpty();
    }

    private void validateExclusiveImageCollections(
            List<String> imageKeys,
            List<ImageCommand> images
    ) {
        if (imageKeys != null && images != null) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
    }

    // (restaurant_id, day_of_week) 유니크 제약도 이미지와 동일한 insert-before-delete 충돌이 있어 같은 방식으로 교체한다.
    private void replaceBusinessHoursWithFlush(
            Restaurant restaurant,
            List<RestaurantBusinessHour> businessHours
    ) {
        restaurant.replaceBusinessHours(List.of());
        restaurantRepository.flush();
        restaurant.replaceBusinessHours(businessHours);
    }

    private RestaurantGenre toGenre(String value) {
        return RestaurantGenre.from(value)
                .orElseThrow(() -> new BusinessException(RestaurantErrorCode.UNSUPPORTED_GENRE));
    }

    private RestaurantPlaceType toPlaceType(String value) {
        return RestaurantPlaceType.from(value)
                .orElseThrow(() -> new BusinessException(RestaurantErrorCode.UNSUPPORTED_PLACE_TYPE));
    }

    private PriceCurrency toPriceCurrency(String value) {
        return PriceCurrency.from(value)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.INVALID_INPUT));
    }

    private List<RestaurantImage> toImagesForCreate(AdminRestaurantCommand command) {
        if (!hasExactlyOneCreateImageSource(command.imageKeys(), command.imageAssetIds())) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        if (command.imageKeys() != null) {
            return toLegacyImages(command.imageKeys());
        }
        return IntStream.range(0, command.imageAssetIds().size())
                .mapToObj(index -> RestaurantImage.createAsset(
                        requireAssetId(command.imageAssetIds().get(index)), index + 1))
                .toList();
    }

    private List<RestaurantImage> toLegacyImages(List<String> imageKeys) {
        return IntStream.range(0, imageKeys.size())
                .mapToObj(index -> RestaurantImage.createLegacy(
                        requireLegacyKey(imageKeys.get(index)), index + 1))
                .toList();
    }

    private List<RestaurantMenu> toMenus(List<MenuCommand> menus) {
        if (menus == null) {
            return List.of();
        }
        if (menus.stream().anyMatch(Objects::isNull)) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        if (menus.stream().anyMatch(menu -> menu.menuId() != null)) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        return menus.stream()
                .map(this::toNewMenu)
                .toList();
    }

    private RestaurantMenu toNewMenu(MenuCommand command) {
        validateMenuImageSource(command);
        PriceCurrency currency = toPriceCurrency(command.priceCurrency());
        if (command.imageAssetId() != null) {
            return RestaurantMenu.createWithAsset(
                    command.name(),
                    command.description(),
                    requireAssetId(command.imageAssetId()),
                    currency,
                    command.priceAmount(),
                    command.main()
            );
        }
        return RestaurantMenu.create(
                command.name(),
                command.description(),
                command.imageKey() == null ? null : requireLegacyKey(command.imageKey()),
                currency,
                command.priceAmount(),
                command.main()
        );
    }

    private RestaurantImageUpdatePlan planImageUpdate(
            Restaurant restaurant,
            AdminRestaurantCommand command
    ) {
        if (command.imageKeys() == null && command.images() == null) {
            return RestaurantImageUpdatePlan.unchanged();
        }
        List<RestaurantImage> existingImages = restaurant.getImages().stream()
                .sorted(Comparator.comparingInt(RestaurantImage::getDisplayOrder))
                .toList();
        if (command.imageKeys() != null) {
            return planLegacyImageUpdate(existingImages, command.imageKeys());
        }
        return planAssetImageUpdate(existingImages, command.images());
    }

    private RestaurantImageUpdatePlan planLegacyImageUpdate(
            List<RestaurantImage> existingImages,
            List<String> imageKeys
    ) {
        if (existingImages.stream().anyMatch(image -> image.getFileKey() == null)) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        Map<String, Deque<RestaurantImage>> imagesByKey = new LinkedHashMap<>();
        existingImages.forEach(image -> imagesByKey
                .computeIfAbsent(image.getFileKey(), ignored -> new ArrayDeque<>())
                .addLast(image));

        List<RestaurantImage> finalImages = new ArrayList<>();
        Set<Long> retainedIds = new HashSet<>();
        for (String rawKey : imageKeys) {
            String imageKey = requireLegacyKey(rawKey);
            Deque<RestaurantImage> candidates = imagesByKey.get(imageKey);
            RestaurantImage retained = candidates == null ? null : candidates.pollFirst();
            if (retained == null) {
                finalImages.add(RestaurantImage.createLegacy(imageKey, 1));
            } else {
                finalImages.add(retained);
                retainedIds.add(retained.getId());
            }
        }
        List<MediaAssetUse> retires = existingImages.stream()
                .filter(image -> !retainedIds.contains(image.getId()))
                .map(RestaurantImage::getImageAssetId)
                .filter(Objects::nonNull)
                .map(assetId -> new MediaAssetUse(assetId, MediaAssetPurpose.RESTAURANT))
                .toList();
        return new RestaurantImageUpdatePlan(
                true, finalImages, retainedIds, List.of(), retires);
    }

    private RestaurantImageUpdatePlan planAssetImageUpdate(
            List<RestaurantImage> existingImages,
            List<ImageCommand> commands
    ) {
        if (commands == null || commands.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        Map<Long, RestaurantImage> existingById = existingImages.stream()
                .collect(Collectors.toMap(RestaurantImage::getId, Function.identity()));
        Set<Long> retainedIds = new HashSet<>();
        List<RestaurantImage> finalImages = new ArrayList<>();
        List<MediaAssetUse> claims = new ArrayList<>();

        for (ImageCommand image : commands) {
            if (image == null
                    || (image.restaurantImageId() == null) == (image.imageAssetId() == null)) {
                throw new BusinessException(CommonErrorCode.INVALID_INPUT);
            }
            if (image.restaurantImageId() != null) {
                if (!retainedIds.add(image.restaurantImageId())) {
                    throw new BusinessException(CommonErrorCode.INVALID_INPUT);
                }
                RestaurantImage retained = existingById.get(image.restaurantImageId());
                if (retained == null) {
                    throw new BusinessException(CommonErrorCode.INVALID_INPUT);
                }
                finalImages.add(retained);
                continue;
            }
            UUID assetId = requireAssetId(image.imageAssetId());
            finalImages.add(RestaurantImage.createAsset(assetId, 1));
            claims.add(new MediaAssetUse(assetId, MediaAssetPurpose.RESTAURANT));
        }

        List<MediaAssetUse> retires = existingImages.stream()
                .filter(image -> !retainedIds.contains(image.getId()))
                .map(RestaurantImage::getImageAssetId)
                .filter(Objects::nonNull)
                .map(assetId -> new MediaAssetUse(assetId, MediaAssetPurpose.RESTAURANT))
                .toList();
        return new RestaurantImageUpdatePlan(
                true, finalImages, retainedIds, claims, retires);
    }

    private void applyImageUpdate(
            Restaurant restaurant,
            RestaurantImageUpdatePlan plan
    ) {
        if (!plan.requested()) {
            return;
        }
        int maxCurrentOrder = restaurant.getImages().stream()
                .mapToInt(RestaurantImage::getDisplayOrder)
                .max()
                .orElse(0);
        int temporaryStart = Math.addExact(
                Math.max(maxCurrentOrder, plan.finalImages().size()), 1);
        restaurant.moveImagesToTemporaryOrders(temporaryStart);
        restaurantRepository.flush();

        restaurant.removeImagesNotIn(plan.retainedImageIds());
        plan.finalImages().stream()
                .filter(image -> image.getId() == null)
                .forEach(restaurant::addImage);
        IntStream.range(0, plan.finalImages().size())
                .forEach(index -> plan.finalImages().get(index).setDisplayOrder(index + 1));
        restaurant.sortImagesByDisplayOrder();
        restaurantRepository.flush();
    }

    private RestaurantMenuUpdatePlan planMenuUpdate(
            Restaurant restaurant,
            List<MenuCommand> commands
    ) {
        if (commands == null) {
            return RestaurantMenuUpdatePlan.unchanged();
        }
        Map<Long, RestaurantMenu> existingById = restaurant.getMenus().stream()
                .filter(menu -> menu.getId() != null)
                .collect(Collectors.toMap(RestaurantMenu::getId, Function.identity()));
        Set<Long> retainedIds = new HashSet<>();
        List<MenuMutation> mutations = new ArrayList<>();
        List<MediaAssetUse> claims = new ArrayList<>();
        List<MediaAssetUse> retires = new ArrayList<>();

        for (MenuCommand command : commands) {
            if (command == null) {
                throw new BusinessException(CommonErrorCode.INVALID_INPUT);
            }
            validateMenuImageSource(command);
            PriceCurrency currency = toPriceCurrency(command.priceCurrency());
            if (command.menuId() == null) {
                UUID newAssetId = command.imageAssetId() == null
                        ? null
                        : requireAssetId(command.imageAssetId());
                if (newAssetId != null) {
                    claims.add(new MediaAssetUse(
                            newAssetId, MediaAssetPurpose.RESTAURANT_MENU));
                }
                mutations.add(new MenuMutation(
                        null,
                        command,
                        command.imageKey() == null ? null : requireLegacyKey(command.imageKey()),
                        newAssetId,
                        currency
                ));
                continue;
            }

            if (!retainedIds.add(command.menuId())) {
                throw new BusinessException(CommonErrorCode.INVALID_INPUT);
            }
            RestaurantMenu existing = existingById.get(command.menuId());
            if (existing == null) {
                throw new BusinessException(RestaurantErrorCode.MENU_NOT_FOUND);
            }
            ResolvedMenuImage image = resolveMenuImage(existing, command, claims, retires);
            mutations.add(new MenuMutation(
                    existing, command, image.imageKey(), image.imageAssetId(), currency));
        }

        existingById.values().stream()
                .filter(menu -> !retainedIds.contains(menu.getId()))
                .map(RestaurantMenu::getImageAssetId)
                .filter(Objects::nonNull)
                .map(assetId -> new MediaAssetUse(assetId, MediaAssetPurpose.RESTAURANT_MENU))
                .forEach(retires::add);
        return new RestaurantMenuUpdatePlan(
                true, mutations, retainedIds, claims, retires);
    }

    private ResolvedMenuImage resolveMenuImage(
            RestaurantMenu existing,
            MenuCommand command,
            List<MediaAssetUse> claims,
            List<MediaAssetUse> retires
    ) {
        UUID currentAssetId = existing.getImageAssetId();
        if (command.imageAssetId() != null) {
            UUID nextAssetId = requireAssetId(command.imageAssetId());
            if (Objects.equals(currentAssetId, nextAssetId)) {
                return new ResolvedMenuImage(existing.getImageKey(), currentAssetId);
            }
            addMenuRetire(currentAssetId, retires);
            claims.add(new MediaAssetUse(nextAssetId, MediaAssetPurpose.RESTAURANT_MENU));
            return new ResolvedMenuImage(null, nextAssetId);
        }
        if (command.imageKey() != null) {
            String nextImageKey = requireLegacyKey(command.imageKey());
            if (Objects.equals(existing.getImageKey(), nextImageKey)) {
                return new ResolvedMenuImage(existing.getImageKey(), currentAssetId);
            }
            addMenuRetire(currentAssetId, retires);
            return new ResolvedMenuImage(nextImageKey, null);
        }
        addMenuRetire(currentAssetId, retires);
        return new ResolvedMenuImage(null, null);
    }

    private void applyMenuUpdate(
            Restaurant restaurant,
            RestaurantMenuUpdatePlan plan
    ) {
        if (!plan.requested()) {
            return;
        }
        plan.mutations().stream()
                .filter(mutation -> mutation.existing() != null)
                .forEach(mutation -> mutation.existing().update(
                        mutation.command().name(),
                        mutation.command().description(),
                        mutation.imageKey(),
                        mutation.imageAssetId(),
                        mutation.currency(),
                        mutation.command().priceAmount(),
                        mutation.command().main()
                ));
        restaurant.removeMenusNotIn(plan.retainedMenuIds());
        plan.mutations().stream()
                .filter(mutation -> mutation.existing() == null)
                .map(this::toNewMenu)
                .forEach(restaurant::addMenu);
    }

    private RestaurantMenu toNewMenu(MenuMutation mutation) {
        MenuCommand command = mutation.command();
        if (mutation.imageAssetId() != null) {
            return RestaurantMenu.createWithAsset(
                    command.name(), command.description(), mutation.imageAssetId(),
                    mutation.currency(), command.priceAmount(), command.main());
        }
        return RestaurantMenu.create(
                command.name(), command.description(), mutation.imageKey(),
                mutation.currency(), command.priceAmount(), command.main());
    }

    private void validateMenuImageSource(MenuCommand command) {
        if (command.imageKey() != null && command.imageAssetId() != null) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
    }

    private void addMenuRetire(UUID assetId, List<MediaAssetUse> retires) {
        if (assetId != null) {
            retires.add(new MediaAssetUse(assetId, MediaAssetPurpose.RESTAURANT_MENU));
        }
    }

    private String requireLegacyKey(String imageKey) {
        if (imageKey == null || imageKey.isBlank()) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        return imageKey;
    }

    private UUID requireAssetId(UUID assetId) {
        if (assetId == null) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        return assetId;
    }

    private void reconcileMediaBindings(
            List<MediaAssetUse> claims,
            List<MediaAssetUse> retires
    ) {
        if (!claims.isEmpty() || !retires.isEmpty()) {
            mediaPort.reconcileBindings(claims, retires);
        }
    }

    private List<RestaurantBusinessHour> toBusinessHours(List<BusinessHourCommand> businessHours) {
        validateBusinessHourCoverage(businessHours);
        try {
            return businessHours.stream()
                    .map(hour -> RestaurantBusinessHour.create(hour.dayOfWeek(), hour.openTime(),
                            hour.closeTime(), hour.breakStart(), hour.breakEnd(), hour.closed()))
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
        List<RestaurantImage> orderedImages = orderedImages(restaurant, Integer.MAX_VALUE);
        List<RestaurantMenu> menus = List.copyOf(restaurant.getMenus());
        MediaProjection mediaProjection = loadAdminProjection(orderedImages, menus);
        ProjectedImage thumbnail = orderedImages.isEmpty()
                ? ProjectedImage.empty()
                : projectImage(
                        orderedImages.getFirst(),
                        MediaImageRole.RESTAURANT_THUMBNAIL,
                        mediaProjection);

        return new AdminRestaurantInfo(
                restaurant.getId(),
                restaurant.getName(),
                restaurant.getLocalName(),
                restaurant.getSummary(),
                restaurant.getDescription(),
                restaurant.getAddress(),
                restaurant.getArea(),
                restaurant.getGenre().value(),
                restaurant.getFoodCategory(),
                restaurant.getPlaceType().value(),
                thumbnail.url(),
                toImageInfo(orderedImages.isEmpty() ? null : orderedImages.getFirst(), thumbnail),
                restaurant.getPriceCurrency().value(),
                restaurant.getMinPrice(),
                restaurant.getMaxPrice(),
                restaurant.isDeleted(),
                toImageUrls(orderedImages, MediaImageRole.RESTAURANT_HERO, mediaProjection),
                toImageInfos(orderedImages, MediaImageRole.RESTAURANT_HERO, mediaProjection),
                menus.stream()
                        .map(menu -> toAdminMenuInfo(menu, mediaProjection))
                        .toList(),
                List.copyOf(restaurant.getHashtags()),
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
                formatTime(businessHour.getBreakStart()),
                formatTime(businessHour.getBreakEnd()),
                businessHour.isClosed());
    }

    private AdminRestaurantMenuInfo toAdminMenuInfo(
            RestaurantMenu menu,
            MediaProjection mediaProjection
    ) {
        ProjectedImage menuImage = projectMenuImage(
                menu, MediaImageRole.MENU_LIST, mediaProjection);
        return new AdminRestaurantMenuInfo(
                menu.getId(),
                menu.getName(),
                menu.getDescription(),
                menuImage.url(),
                menuImage.image(),
                menu.getPriceCurrency() == null ? null : menu.getPriceCurrency().value(),
                menu.getPriceAmount(),
                menu.isMain());
    }

    // null·중복을 제거하되 요청 순서는 유지한다 — 호출 모듈의 노출 순서(displayOrder)를 그대로 따르기 위함
    private List<Long> distinctIds(Collection<Long> restaurantIds) {
        if (restaurantIds == null) {
            return List.of();
        }
        return restaurantIds.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.collectingAndThen(
                        Collectors.toCollection(LinkedHashSet::new),
                        List::copyOf
                ));
    }

    // 오늘 영업시간은 목록 조회와 같은 일괄 쿼리로 한 번에 가져온다(삭제된 식당은 쿼리가 걸러 null이 된다)
    private List<RestaurantDetailInfo> toDetailInfos(List<Restaurant> restaurants) {
        LocalDate businessDate = LocalDate.now(japanClock);
        Map<Long, RestaurantBusinessHour> businessHours = findBusinessHours(restaurants, businessDate.getDayOfWeek());
        MediaProjection mediaProjection = loadRestaurantProjection(
                restaurants, MediaImageRole.RESTAURANT_CARD, Integer.MAX_VALUE);
        return restaurants.stream()
                .map(restaurant -> toDetailInfo(
                        restaurant, businessDate, businessHours.get(restaurant.getId()), mediaProjection))
                .toList();
    }

    private RestaurantDetailInfo toDetailInfo(Restaurant restaurant, LocalDate businessDate,
                                              RestaurantBusinessHour businessHour,
                                              MediaProjection mediaProjection) {
        return new RestaurantDetailInfo(
                restaurant.getId(),
                restaurant.getName(),
                restaurant.getLocalName(),
                restaurant.getAddress(),
                restaurant.getArea(),
                restaurant.getFoodCategory(),
                toThumbnailReference(restaurant),
                toImageUrls(
                        orderedImages(restaurant, Integer.MAX_VALUE),
                        MediaImageRole.RESTAURANT_CARD,
                        mediaProjection),
                restaurant.getRating(),
                toTodayBusinessHourInfo(businessDate, businessHour),
                new PriceRangeInfo(
                        restaurant.getPriceCurrency().value(),
                        toWholeAmount(restaurant.getMinPrice()),
                        toWholeAmount(restaurant.getMaxPrice()))
        );
    }

    // 대표 이미지는 전환기 참조 값으로만 넘기고, 파생본 선택은 받는 모듈이 맡는다
    private ImageReference toThumbnailReference(Restaurant restaurant) {
        return restaurant.getThumbnailImage()
                .map(image -> new ImageReference(image.getImageAssetId(), resolveLegacyUrl(image.getFileKey())))
                .orElse(null);
    }

    private TodayBusinessHourInfo toTodayBusinessHourInfo(LocalDate businessDate, RestaurantBusinessHour businessHour) {
        if (businessHour == null) {
            return null;
        }
        return new TodayBusinessHourInfo(
                businessDate.toString(),
                businessHour.getDayOfWeek().name(),
                formatTime(businessHour.getOpenTime()),
                formatTime(businessHour.getCloseTime()),
                businessHour.isClosed()
        );
    }

    private record RestaurantImageUpdatePlan(
            boolean requested,
            List<RestaurantImage> finalImages,
            Set<Long> retainedImageIds,
            List<MediaAssetUse> claims,
            List<MediaAssetUse> retires
    ) {

        private RestaurantImageUpdatePlan {
            finalImages = List.copyOf(finalImages);
            retainedImageIds = Set.copyOf(retainedImageIds);
            claims = List.copyOf(claims);
            retires = List.copyOf(retires);
        }

        private static RestaurantImageUpdatePlan unchanged() {
            return new RestaurantImageUpdatePlan(
                    false, List.of(), Set.of(), List.of(), List.of());
        }
    }

    private record RestaurantMenuUpdatePlan(
            boolean requested,
            List<MenuMutation> mutations,
            Set<Long> retainedMenuIds,
            List<MediaAssetUse> claims,
            List<MediaAssetUse> retires
    ) {

        private RestaurantMenuUpdatePlan {
            mutations = List.copyOf(mutations);
            retainedMenuIds = Set.copyOf(retainedMenuIds);
            claims = List.copyOf(claims);
            retires = List.copyOf(retires);
        }

        private static RestaurantMenuUpdatePlan unchanged() {
            return new RestaurantMenuUpdatePlan(
                    false, List.of(), Set.of(), List.of(), List.of());
        }
    }

    private record MenuMutation(
            RestaurantMenu existing,
            MenuCommand command,
            String imageKey,
            UUID imageAssetId,
            PriceCurrency currency
    ) {
    }

    private record ResolvedMenuImage(String imageKey, UUID imageAssetId) {
    }

    private record MediaProjection(Map<MediaImageRequest, MediaImage> images) {

        private MediaProjection {
            images = Map.copyOf(images);
        }

        private static MediaProjection empty() {
            return new MediaProjection(Map.of());
        }

        private MediaImage find(UUID assetId, MediaImageRole role) {
            return images.get(new MediaImageRequest(assetId, role));
        }
    }

    private record ProjectedImage(String url, MediaImage image) {

        private static ProjectedImage empty() {
            return new ProjectedImage(null, null);
        }
    }

    private String formatTime(LocalTime time) {
        return time == null ? null : time.format(TIME_FORMATTER);
    }

    private Long toWholeAmount(BigDecimal amount) {
        return amount == null ? null : amount.longValue();
    }
}
