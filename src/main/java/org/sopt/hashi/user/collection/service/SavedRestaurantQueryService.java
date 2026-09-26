package org.sopt.hashi.user.collection.service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.sopt.hashi.media.MediaImageSelection;
import org.sopt.hashi.restaurant.RestaurantCardInfo;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.user.code.UserErrorCode;
import org.sopt.hashi.user.collection.domain.RestaurantCollection;
import org.sopt.hashi.user.collection.domain.SavedRestaurant;
import org.sopt.hashi.user.collection.domain.SavedRestaurantRepository;
import org.sopt.hashi.user.collection.domain.SavedRestaurantSort;
import org.sopt.hashi.user.collection.dto.SavedRestaurantListResponse;
import org.sopt.hashi.user.collection.dto.SavedRestaurantListResponse.SavedRestaurantResponse;
import org.sopt.hashi.user.collection.service.SavedRestaurantCursorCodec.Cursor;
import org.sopt.hashi.user.collection.service.SavedRestaurantEnricher.ThumbnailProjection;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 컬렉션 저장 식당 목록(#216, SAVED-008) — 정렬(최신·별점·리뷰)·음식점 분류 필터·커서 페이지네이션.
 * 평점·리뷰 수는 restaurant 테이블에 있고 모듈 간 조인은 금지라(architecture.md §5), 컬렉션의 저장 식당을 모두 읽어
 * RestaurantPort의 가벼운 카드 조회로 정렬 값을 받은 뒤 메모리에서 정렬·자른다. 컬렉션당 저장 식당 상한
 * ({@link RestaurantCollectionService#MAX_SAVED_RESTAURANTS_PER_COLLECTION})이 이를 감당하며, 대표 이미지 파생본은
 * 잘라낸 한 페이지에 대해서만 조회한다.
 */
@Service
@Transactional(readOnly = true)
public class SavedRestaurantQueryService {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 50;

    private final SavedRestaurantRepository savedRestaurantRepository;
    private final RestaurantCollectionFinder collectionFinder;
    private final SavedRestaurantEnricher enricher;

    public SavedRestaurantQueryService(SavedRestaurantRepository savedRestaurantRepository,
                                       RestaurantCollectionFinder collectionFinder,
                                       SavedRestaurantEnricher enricher) {
        this.savedRestaurantRepository = savedRestaurantRepository;
        this.collectionFinder = collectionFinder;
        this.enricher = enricher;
    }

    /**
     * 저장 식당 목록 — 공개 컬렉션은 누구나, 비공개는 소유자만 조회한다.
     * placeType은 "restaurant"·"cafe"·"bar" 중 하나이며 없으면 전체다. 삭제된 식당은 제외한다.
     */
    public SavedRestaurantListResponse getSavedRestaurants(Long collectionId, String sortValue, String placeType,
                                                           String cursorValue, Integer size) {
        RestaurantCollection collection = collectionFinder.findVisible(collectionId);
        SavedRestaurantSort sort = toSort(sortValue);
        Cursor cursor = SavedRestaurantCursorCodec.decode(cursorValue, sort);
        int pageSize = normalizeSize(size);

        List<SavedRestaurant> saved = savedRestaurantRepository.findAllByCollection_IdOrderByIdDesc(collection.getId());
        Map<Long, RestaurantCardInfo> cards = enricher.findActiveCards(
                saved.stream().map(SavedRestaurant::getRestaurantId).collect(Collectors.toSet()));
        List<SavedRestaurantItem> rows = saved.stream()
                .filter(item -> cards.containsKey(item.getRestaurantId()))
                .map(item -> new SavedRestaurantItem(item, cards.get(item.getRestaurantId())))
                .filter(placeTypeFilter(placeType))
                .sorted(comparator(sort))
                .filter(afterCursor(cursor))
                .limit(pageSize + 1L)
                .toList();

        boolean hasNext = rows.size() > pageSize;
        List<SavedRestaurantItem> page = hasNext ? rows.subList(0, pageSize) : rows;
        String nextCursor = hasNext ? SavedRestaurantCursorCodec.encode(sort, page.getLast()) : null;
        ThumbnailProjection thumbnails = enricher.loadThumbnails(
                page.stream().map(SavedRestaurantItem::card).toList());

        return new SavedRestaurantListResponse(
                page.stream().map(item -> toResponse(item, thumbnails)).toList(),
                nextCursor,
                hasNext);
    }

    private SavedRestaurantResponse toResponse(SavedRestaurantItem item, ThumbnailProjection thumbnails) {
        RestaurantCardInfo card = item.card();
        MediaImageSelection thumbnail = thumbnails.select(card);
        return new SavedRestaurantResponse(
                card.id(),
                card.name(),
                card.rating(),
                card.reviewCount(),
                card.area(),
                card.foodCategory(),
                card.placeType(),
                thumbnail.url(),
                thumbnail.image(),
                item.savedAt());
    }

    private Predicate<SavedRestaurantItem> placeTypeFilter(String placeType) {
        if (placeType == null || placeType.isBlank()) {
            return item -> true;
        }
        return item -> placeType.equals(item.card().placeType());
    }

    /** 별점·리뷰 동점은 식당 id 역순으로 고정해 커서가 항상 같은 순서를 재현하게 한다. */
    private Comparator<SavedRestaurantItem> comparator(SavedRestaurantSort sort) {
        Comparator<SavedRestaurantItem> byRestaurantIdDesc =
                Comparator.comparing(SavedRestaurantItem::restaurantId).reversed();
        return switch (sort) {
            case LATEST -> Comparator.comparingLong(SavedRestaurantItem::savedId).reversed();
            case RATING -> Comparator.comparing(SavedRestaurantItem::rating).reversed().thenComparing(byRestaurantIdDesc);
            case REVIEW -> Comparator.comparingLong(SavedRestaurantItem::reviewCount).reversed()
                    .thenComparing(byRestaurantIdDesc);
        };
    }

    private Predicate<SavedRestaurantItem> afterCursor(Cursor cursor) {
        if (cursor == null) {
            return item -> true;
        }
        return switch (cursor.sort()) {
            case LATEST -> item -> item.savedId() < cursor.id();
            case RATING -> item -> {
                int compared = item.rating().compareTo(cursor.rating());
                return compared < 0 || (compared == 0 && item.restaurantId() < cursor.id());
            };
            case REVIEW -> item -> item.reviewCount() < cursor.reviewCount()
                    || (item.reviewCount() == cursor.reviewCount() && item.restaurantId() < cursor.id());
        };
    }

    private SavedRestaurantSort toSort(String value) {
        if (value == null || value.isBlank()) {
            return SavedRestaurantSort.LATEST;
        }
        return SavedRestaurantSort.from(value)
                .orElseThrow(() -> new BusinessException(UserErrorCode.UNSUPPORTED_SAVED_RESTAURANT_SORT));
    }

    private int normalizeSize(Integer size) {
        if (size == null || size < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
