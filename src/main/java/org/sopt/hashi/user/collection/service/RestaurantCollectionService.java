package org.sopt.hashi.user.collection.service;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.sopt.hashi.auth.CurrentUserProvider;
import org.sopt.hashi.restaurant.RestaurantCardInfo;
import org.sopt.hashi.restaurant.RestaurantPort;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.user.code.UserErrorCode;
import org.sopt.hashi.user.collection.domain.CollectionColor;
import org.sopt.hashi.user.collection.domain.CollectionVisibility;
import org.sopt.hashi.user.collection.domain.RestaurantCollection;
import org.sopt.hashi.user.collection.domain.RestaurantCollectionRepository;
import org.sopt.hashi.user.collection.domain.SavedRestaurant;
import org.sopt.hashi.user.collection.domain.SavedRestaurantRepository;
import org.sopt.hashi.user.collection.dto.CreateRestaurantCollectionRequest;
import org.sopt.hashi.user.collection.dto.MoveSavedRestaurantsRequest;
import org.sopt.hashi.user.collection.dto.MoveSavedRestaurantsResponse;
import org.sopt.hashi.user.collection.dto.RestaurantCollectionListResponse;
import org.sopt.hashi.user.collection.dto.RestaurantCollectionListResponse.RestaurantCollectionSummaryResponse;
import org.sopt.hashi.user.collection.dto.RestaurantCollectionResponse;
import org.sopt.hashi.user.collection.dto.RestaurantCollectionResponse.CoverResponse;
import org.sopt.hashi.user.collection.dto.SaveRestaurantRequest;
import org.sopt.hashi.user.collection.dto.UpdateRestaurantCollectionRequest;
import org.sopt.hashi.user.collection.service.SavedRestaurantEnricher.ThumbnailProjection;
import org.sopt.hashi.user.domain.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 식당 컬렉션 관리(#216, SAVED-004~007) — 생성·수정·삭제·목록과 식당 저장·제거·이동.
 * 소유자 판정은 항상 {@link CurrentUserProvider}의 현재 사용자로 한다(auth.md §2).
 * 저장 식당 수·커버는 삭제된 식당을 뺀 "현재 표시 가능한" 식당 기준이라 응답마다 RestaurantPort의 카드 조회로 확인한다.
 * 컬렉션명·저장 식당 중복은 사전 검사 후 DB 유니크 제약을 최종 방어선으로 두고, 경합으로 제약에 걸리면 409로 바꿔 낸다.
 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class RestaurantCollectionService {

    /** 방어용 상한 — 기획 상한이 없어 메모리 정렬·커버 계산이 감당되는 범위로 둔다. */
    static final int MAX_COLLECTIONS_PER_USER = 400;
    static final int MAX_SAVED_RESTAURANTS_PER_COLLECTION = 1000;
    /** 커버 그리드는 색상 1칸 + 대표 이미지 3칸이다(SAVED 시안). */
    static final int COVER_IMAGE_COUNT = 3;

    private final RestaurantCollectionRepository restaurantCollectionRepository;
    private final SavedRestaurantRepository savedRestaurantRepository;
    private final RestaurantCollectionFinder collectionFinder;
    private final SavedRestaurantEnricher enricher;
    private final RestaurantPort restaurantPort;
    private final UserRepository userRepository;
    private final CurrentUserProvider currentUserProvider;

    public RestaurantCollectionService(RestaurantCollectionRepository restaurantCollectionRepository,
                                       SavedRestaurantRepository savedRestaurantRepository,
                                       RestaurantCollectionFinder collectionFinder,
                                       SavedRestaurantEnricher enricher,
                                       RestaurantPort restaurantPort,
                                       UserRepository userRepository,
                                       CurrentUserProvider currentUserProvider) {
        this.restaurantCollectionRepository = restaurantCollectionRepository;
        this.savedRestaurantRepository = savedRestaurantRepository;
        this.collectionFinder = collectionFinder;
        this.enricher = enricher;
        this.restaurantPort = restaurantPort;
        this.userRepository = userRepository;
        this.currentUserProvider = currentUserProvider;
    }

    /**
     * 새 컬렉션 만들기(SAVED-006) — 같은 사용자의 컬렉션명은 중복될 수 없다. 공개 범위는 요청에서 필수로 받는다.
     * 저장 전에 회원 행을 잠가 탈퇴(회원 행 잠금 뒤 컬렉션 삭제)와 엇갈려 탈퇴 회원의 컬렉션이 남지 않게 한다(architecture.md §8).
     */
    @Transactional
    public RestaurantCollectionResponse create(CreateRestaurantCollectionRequest request) {
        Long userId = lockCurrentUser();
        if (restaurantCollectionRepository.countByUserId(userId) >= MAX_COLLECTIONS_PER_USER) {
            throw new BusinessException(UserErrorCode.COLLECTION_LIMIT_EXCEEDED);
        }
        String name = normalizeName(request.name());
        if (restaurantCollectionRepository.existsByUserIdAndName(userId, name)) {
            throw new BusinessException(UserErrorCode.DUPLICATE_COLLECTION_NAME);
        }
        String description = normalizeDescription(request.description());
        RestaurantCollection collection = saveNewOrConflict(RestaurantCollection.create(
                userId, name, toColor(request.color()),
                description == null || description.isEmpty() ? null : description,
                toVisibility(request.visibility())));
        log.info("컬렉션 생성. collectionId={}, userId={}", collection.getId(), userId);
        return new RestaurantCollectionResponse(
                collection.getId(), collection.getName(), collection.getColor().value(),
                collection.getDescription(), collection.getVisibility().value(),
                0, new CoverResponse(collection.getColor().value(), List.of()), true, collection.getCreatedAt());
    }

    /**
     * 내 컬렉션 목록(SAVED-007·008·저장 모달·이동 모달) — 생성일 최신순 커서 페이지네이션(SAVED_COLLECTION_MANAGE "목록 추가 조회").
     * 커서는 직전 페이지 마지막 컬렉션 id다. restaurantId가 있으면 항목마다 그 식당의 저장 여부를 함께 내려
     * 저장 모달이 "이미 저장됨"을 표시할 수 있게 한다.
     */
    public RestaurantCollectionListResponse getMyCollections(Long restaurantId, Long cursor, Integer size) {
        Long userId = currentUserProvider.currentUserId();
        int pageSize = normalizeSize(size);
        List<RestaurantCollection> rows = fetchPage(userId, cursor, PageRequest.of(0, pageSize + 1));
        boolean hasNext = rows.size() > pageSize;
        List<RestaurantCollection> collections = hasNext ? rows.subList(0, pageSize) : rows;
        Long nextCursor = hasNext ? collections.getLast().getId() : null;
        long totalCount = restaurantCollectionRepository.countByUserId(userId);
        if (collections.isEmpty()) {
            return new RestaurantCollectionListResponse(List.of(), totalCount, null, false);
        }
        // (컬렉션 ID, 저장된 식당 리스트)로 savedByCollection에 저장
        Map<Long, List<SavedRestaurant>> savedByCollection = savedRestaurantRepository
                .findAllByCollection_IdInOrderByIdAsc(collections.stream().map(RestaurantCollection::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(saved -> saved.getCollection().getId()));
        // RestaurantPort로 표시 가능한 식당의 카드 정보를 한 번에 조회
        Map<Long, RestaurantCardInfo> cards = enricher.findActiveCards(savedByCollection.values().stream()
                .flatMap(List::stream)
                .map(SavedRestaurant::getRestaurantId)
                .collect(Collectors.toSet()));
        Map<Long, List<RestaurantCardInfo>> activeByCollection = collections.stream()
                .collect(Collectors.toMap(RestaurantCollection::getId, collection ->
                        activeCards(savedByCollection.getOrDefault(collection.getId(), List.of()), cards)));
        // 대표 이미지 파생본은 컬렉션별 커버 후보(최대 3장)만 모아 한 번에 조회한다
        ThumbnailProjection thumbnails = enricher.loadThumbnails(activeByCollection.values().stream()
                .flatMap(active -> coverCandidates(active).stream())
                .toList());

        return new RestaurantCollectionListResponse(collections.stream()
                .map(collection -> {
                    List<RestaurantCardInfo> active = activeByCollection.get(collection.getId());
                    Boolean saved = restaurantId == null
                            ? null
                            : savedByCollection.getOrDefault(collection.getId(), List.of()).stream()
                                    .anyMatch(item -> item.getRestaurantId().equals(restaurantId));
                    return new RestaurantCollectionSummaryResponse(
                            collection.getId(), collection.getName(), collection.getColor().value(),
                            collection.getVisibility().value(), active.size(),
                            toCover(collection, active, thumbnails), saved);
                })
                .toList(), totalCount, nextCursor, hasNext);
    }

    private List<RestaurantCollection> fetchPage(Long userId, Long cursor, Pageable pageable) {
        return cursor == null
                ? restaurantCollectionRepository.findAllByUserIdOrderByIdDesc(userId, pageable)
                : restaurantCollectionRepository.findAllByUserIdAndIdLessThanOrderByIdDesc(userId, cursor, pageable);
    }

    private int normalizeSize(Integer size) {
        if (size == null || size < 1) {
            return SavedRestaurantQueryService.DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, SavedRestaurantQueryService.MAX_PAGE_SIZE);
    }

    /** 컬렉션 상세 헤더(SAVED-004) — 공개 컬렉션은 비로그인도 조회할 수 있고, 비공개는 소유자만. */
    public RestaurantCollectionResponse getCollection(Long collectionId) {
        RestaurantCollection collection = collectionFinder.findVisible(collectionId);
        return toResponse(collection, collectionFinder.isOwner(collection));
    }

    /** 컬렉션 수정(SAVED-007) — 부분 수정. 이름을 바꾸면 다른 컬렉션과 중복될 수 없다. */
    @Transactional
    public RestaurantCollectionResponse update(Long collectionId, UpdateRestaurantCollectionRequest request) {
        RestaurantCollection collection = collectionFinder.findOwnedForUpdate(collectionId);
        String name = request.name() == null ? null : normalizeName(request.name());
        boolean isRenamingToTakenName = name != null
                && restaurantCollectionRepository.existsByUserIdAndNameAndIdNot(
                        collection.getUserId(), name, collection.getId());
        if (isRenamingToTakenName) {
            throw new BusinessException(UserErrorCode.DUPLICATE_COLLECTION_NAME);
        }
        collection.update(
                name,
                request.color() == null ? null : toColor(request.color()),
                normalizeDescription(request.description()),
                request.visibility() == null ? null : toVisibility(request.visibility()));
        flushOrConflict(UserErrorCode.DUPLICATE_COLLECTION_NAME);
        return toResponse(collection, true);
    }

    /** 컬렉션 삭제(SAVED-007) — 컬렉션과 저장 관계만 지우고 식당 원본은 건드리지 않는다. 마지막 저장 관계가 사라진 식당은 저장 수를 줄인다. */
    @Transactional
    public void delete(Long collectionId) {
        Long userId = lockCurrentUser();
        RestaurantCollection collection = collectionFinder.findOwnedForUpdate(collectionId);
        Set<Long> removed = collection.getSavedRestaurants().stream()
                .map(SavedRestaurant::getRestaurantId)
                .collect(Collectors.toSet());
        restaurantCollectionRepository.delete(collection);
        decreaseSaveCountsForUnsaved(userId, removed);
        log.info("컬렉션 삭제. collectionId={}, userId={}", collection.getId(), collection.getUserId());
    }

    /**
     * 식당 저장(SAVED-006) — 같은 식당을 같은 컬렉션에 두 번 저장할 수 없다(다른 컬렉션에는 가능).
     * 삭제된 식당은 목록에 나타날 수 없으므로 저장 자체를 거부한다.
     */
    @Transactional
    public RestaurantCollectionResponse saveRestaurant(Long collectionId, SaveRestaurantRequest request) {
        Long userId = lockCurrentUser();
        RestaurantCollection collection = collectionFinder.findOwnedForUpdate(collectionId);
        Long restaurantId = request.restaurantId();
        if (!restaurantPort.existsActiveById(restaurantId)) {
            throw new BusinessException(UserErrorCode.RESTAURANT_NOT_FOUND);
        }
        if (collection.contains(restaurantId)) {
            throw new BusinessException(UserErrorCode.RESTAURANT_ALREADY_SAVED);
        }
        requireCapacity(collection, 1);
        boolean isFirstSave = savedRestaurantRepository.findSavedRestaurantIds(userId, List.of(restaurantId)).isEmpty();
        collection.save(restaurantId);
        flushOrConflict(UserErrorCode.RESTAURANT_ALREADY_SAVED);
        if (isFirstSave) {
            restaurantPort.increaseSaveCount(restaurantId);
        }
        return toResponse(collection, true);
    }

    /** 선택 식당 제거(SAVED-004) — 하나라도 컬렉션에 없으면 전체를 거부해 목록·선택 상태를 유지시킨다. */
    @Transactional
    public RestaurantCollectionResponse removeRestaurants(Long collectionId, List<Long> restaurantIds) {
        Set<Long> targets = distinctIds(restaurantIds);
        Long userId = lockCurrentUser();
        RestaurantCollection collection = collectionFinder.findOwnedForUpdate(collectionId);
        requireAllSaved(collection, targets);
        collection.remove(targets);
        decreaseSaveCountsForUnsaved(userId, targets);
        return toResponse(collection, true);
    }

    /**
     * 선택 식당 이동(SAVED-004) — 원래 컬렉션에서 빼고 대상 컬렉션에 넣는다(한 트랜잭션).
     * 대상에 이미 있는 식당이 하나라도 있으면 기획 확정대로 전체 실패한다.
     */
    @Transactional
    public MoveSavedRestaurantsResponse moveRestaurants(Long collectionId, MoveSavedRestaurantsRequest request) {
        if (collectionId.equals(request.targetCollectionId())) {
            throw new BusinessException(UserErrorCode.SAME_COLLECTION_MOVE);
        }
        Set<Long> targets = distinctIds(request.restaurantIds());
        // 반대 방향의 이동도 같은 순서로 두 부모를 잠근 후 자식 목록을 읽는다.
        Long firstId = Math.min(collectionId, request.targetCollectionId());
        Long secondId = Math.max(collectionId, request.targetCollectionId());
        RestaurantCollection first = collectionFinder.findOwnedForUpdate(firstId);
        RestaurantCollection second = collectionFinder.findOwnedForUpdate(secondId);
        RestaurantCollection source = collectionId.equals(firstId) ? first : second;
        RestaurantCollection target = collectionId.equals(firstId) ? second : first;
        requireAllSaved(source, targets);
        boolean isAnyAlreadyInTarget = targets.stream().anyMatch(target::contains);
        if (isAnyAlreadyInTarget) {
            throw new BusinessException(UserErrorCode.RESTAURANT_ALREADY_SAVED);
        }
        requireCapacity(target, targets.size());

        source.remove(targets);
        targets.forEach(target::save);
        flushOrConflict(UserErrorCode.RESTAURANT_ALREADY_SAVED);
        return new MoveSavedRestaurantsResponse(toResponse(source, true), toResponse(target, true));
    }

    /**
     * 식당 저장 수(#263)의 첫 저장·마지막 제거 판정은 같은 회원의 다른 컬렉션 쓰기와 엇갈리면 틀리므로
     * 회원 행을 먼저 잠가 회원 단위로 직렬화한다. 잠금 순서는 회원 → 컬렉션 → 식당(포트의 UPDATE)으로 고정한다.
     * 탈퇴(회원 행 잠금 뒤 컬렉션 삭제)와도 엇갈리지 않는다.
     */
    private Long lockCurrentUser() {
        Long userId = currentUserProvider.currentUserId();
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.NOT_FOUND));
        return userId;
    }

    /** 제거한 식당 중 회원의 다른 컬렉션에도 남지 않은 것만 저장 수를 1 감소시킨다(마지막 저장 관계 삭제). */
    private void decreaseSaveCountsForUnsaved(Long userId, Collection<Long> removedRestaurantIds) {
        if (removedRestaurantIds.isEmpty()) {
            return;
        }
        // orphanRemoval 삭제를 먼저 반영해야 남은 관계만 센다
        restaurantCollectionRepository.flush();
        Set<Long> stillSaved = new HashSet<>(
                savedRestaurantRepository.findSavedRestaurantIds(userId, removedRestaurantIds));
        List<Long> unsaved = removedRestaurantIds.stream()
                .filter(id -> !stillSaved.contains(id))
                .sorted()
                .toList();
        if (!unsaved.isEmpty()) {
            restaurantPort.decreaseSaveCounts(unsaved);
        }
    }

    private RestaurantCollectionResponse toResponse(RestaurantCollection collection, boolean owner) {
        List<SavedRestaurant> saved = collection.getSavedRestaurants();
        Map<Long, RestaurantCardInfo> cards = enricher.findActiveCards(
                saved.stream().map(SavedRestaurant::getRestaurantId).collect(Collectors.toSet()));
        List<RestaurantCardInfo> active = activeCards(saved, cards);
        ThumbnailProjection thumbnails = enricher.loadThumbnails(coverCandidates(active));
        return new RestaurantCollectionResponse(
                collection.getId(),
                collection.getName(),
                collection.getColor().value(),
                collection.getDescription(),
                collection.getVisibility().value(),
                active.size(),
                toCover(collection, active, thumbnails),
                owner,
                collection.getCreatedAt());
    }

    /** 저장 순서(id 오름차순)를 유지하며 표시 가능한 식당만 남긴다. */
    private List<RestaurantCardInfo> activeCards(List<SavedRestaurant> saved, Map<Long, RestaurantCardInfo> cards) {
        return saved.stream()
                .map(item -> cards.get(item.getRestaurantId()))
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 커버 후보 — 저장 순으로 대표 이미지 참조가 있는 식당 최대 3개. 이미지 파생본 조회를 이 후보에만 한정한다.
     * 변환이 아직 안 끝난 asset이 후보에 들면 그 칸은 비지만, 짧고 드문 상태라 감수한다.
     */
    private List<RestaurantCardInfo> coverCandidates(List<RestaurantCardInfo> active) {
        return active.stream()
                .filter(card -> card.thumbnailImageReference() != null)
                .limit(COVER_IMAGE_COUNT)
                .toList();
    }

    /** 커버 이미지 — 후보 순서대로 URL이 있는 것만. 이미지가 없거나 아직 준비되지 않은 식당은 건너뛴다. */
    private CoverResponse toCover(RestaurantCollection collection, List<RestaurantCardInfo> active,
                                  ThumbnailProjection thumbnails) {
        List<String> imageUrls = coverCandidates(active).stream()
                .map(card -> thumbnails.select(card).url())
                .filter(Objects::nonNull)
                .toList();
        return new CoverResponse(collection.getColor().value(), imageUrls);
    }

    /**
     * 새 컬렉션 저장 — IDENTITY 전략이라 INSERT가 flush가 아니라 save() 시점에 바로 실행되므로, 이름 유니크 경합은
     * 저장 호출 자체에서 터진다. 저장을 감싸 500 대신 409 DUPLICATE_COLLECTION_NAME으로 낸다.
     */
    private RestaurantCollection saveNewOrConflict(RestaurantCollection collection) {
        try {
            return restaurantCollectionRepository.saveAndFlush(collection);
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(UserErrorCode.DUPLICATE_COLLECTION_NAME, exception);
        }
    }

    /**
     * 사전 검사와 저장 사이의 경합으로 유니크 제약(컬렉션명·컬렉션 내 식당)에 걸리면 500이 아니라 해당 409로 낸다.
     * 트랜잭션 안에서 flush해야 제약 위반이 이 메서드에서 잡히고, 던진 예외로 트랜잭션은 롤백된다.
     */
    private void flushOrConflict(UserErrorCode conflict) {
        try {
            restaurantCollectionRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(conflict, exception);
        }
    }

    private void requireAllSaved(RestaurantCollection collection, Collection<Long> restaurantIds) {
        boolean isAnyNotSaved = restaurantIds.stream().anyMatch(id -> !collection.contains(id));
        if (isAnyNotSaved) {
            throw new BusinessException(UserErrorCode.RESTAURANT_NOT_SAVED);
        }
    }

    private void requireCapacity(RestaurantCollection collection, int additional) {
        if (collection.savedRestaurantCount() + additional > MAX_SAVED_RESTAURANTS_PER_COLLECTION) {
            throw new BusinessException(UserErrorCode.SAVED_RESTAURANT_LIMIT_EXCEEDED);
        }
    }

    private Set<Long> distinctIds(List<Long> restaurantIds) {
        if (restaurantIds == null || restaurantIds.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        Set<Long> ids = new LinkedHashSet<>(restaurantIds);
        ids.remove(null);
        if (ids.isEmpty() || ids.stream().anyMatch(id -> id <= 0)) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        return ids;
    }

    /** 입력 길이 제한은 DTO와 같게 적용하고, 앞뒤 공백을 제거한 이름으로 저장·중복 검사한다. */
    private String normalizeName(String name) {
        if (name == null || name.length() > RestaurantCollection.NAME_MAX_LENGTH) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        String normalized = stripBoundaryWhitespace(name);
        if (normalized.isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        return normalized;
    }

    /** NBSP·전각 공백도 제거하며 이름·설명 안쪽의 공백은 그대로 둔다. */
    private String stripBoundaryWhitespace(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isBlankChar(value.charAt(start))) {
            start++;
        }
        while (end > start && isBlankChar(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    private static boolean isBlankChar(char character) {
        return Character.isWhitespace(character) || Character.isSpaceChar(character) || character == '\u0085';
    }

    private String normalizeDescription(String description) {
        if (description == null) {
            return null;
        }
        if (description.length() > RestaurantCollection.DESCRIPTION_MAX_LENGTH) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        // PATCH는 null이면 유지, 빈 문자열이면 삭제하므로 두 값을 구분한다.
        return stripBoundaryWhitespace(description);
    }

    private CollectionColor toColor(String value) {
        return CollectionColor.from(value)
                .orElseThrow(() -> new BusinessException(UserErrorCode.UNSUPPORTED_COLLECTION_COLOR));
    }

    private CollectionVisibility toVisibility(String value) {
        return CollectionVisibility.from(value)
                .orElseThrow(() -> new BusinessException(UserErrorCode.UNSUPPORTED_COLLECTION_VISIBILITY));
    }
}
