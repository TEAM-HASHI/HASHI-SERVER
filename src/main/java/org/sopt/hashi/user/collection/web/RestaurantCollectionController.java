package org.sopt.hashi.user.collection.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.shared.error.CommonSuccessCode;
import org.sopt.hashi.shared.response.SuccessResponse;
import org.sopt.hashi.shared.swagger.ApiException;
import org.sopt.hashi.shared.swagger.ApiSuccess;
import org.sopt.hashi.user.code.UserErrorCode;
import org.sopt.hashi.user.code.UserSuccessCode;
import org.sopt.hashi.user.collection.dto.CreateRestaurantCollectionRequest;
import org.sopt.hashi.user.collection.dto.MoveSavedRestaurantsRequest;
import org.sopt.hashi.user.collection.dto.MoveSavedRestaurantsResponse;
import org.sopt.hashi.user.collection.dto.RestaurantCollectionListResponse;
import org.sopt.hashi.user.collection.dto.RestaurantCollectionResponse;
import org.sopt.hashi.user.collection.dto.SaveRestaurantRequest;
import org.sopt.hashi.user.collection.dto.SavedRestaurantListResponse;
import org.sopt.hashi.user.collection.dto.UpdateRestaurantCollectionRequest;
import org.sopt.hashi.user.collection.service.RestaurantCollectionService;
import org.sopt.hashi.user.collection.service.SavedRestaurantQueryService;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 식당 컬렉션 API(#216, SAVED-004~008). 컬렉션 상세·저장 식당 목록 GET은 공개 컬렉션에 한해 비로그인도 조회할 수
 * 있고(SecurityConfig의 GET permitAll), 나머지는 로그인 회원 전용이며 소유자만 편집할 수 있다(남의 컬렉션은 403).
 * 공유 링크 복사는 클라이언트가 처리하고 서버는 공개 범위 변경(PATCH)만 담당한다.
 */
@Validated
@RestController
@RequestMapping("/api/v1/collections")
public class RestaurantCollectionController {

    private final RestaurantCollectionService restaurantCollectionService;
    private final SavedRestaurantQueryService savedRestaurantQueryService;

    public RestaurantCollectionController(RestaurantCollectionService restaurantCollectionService,
                                          SavedRestaurantQueryService savedRestaurantQueryService) {
        this.restaurantCollectionService = restaurantCollectionService;
        this.savedRestaurantQueryService = savedRestaurantQueryService;
    }

    /** 새 컬렉션 만들기 — 공개 범위(public·private)는 필수다. 같은 이름의 컬렉션이 있으면 USER-007. */
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT", "UNAUTHORIZED", "FORBIDDEN"})
    @ApiException(value = UserErrorCode.class, codes = {"DUPLICATE_COLLECTION_NAME", "UNSUPPORTED_COLLECTION_COLOR",
            "UNSUPPORTED_COLLECTION_VISIBILITY", "COLLECTION_LIMIT_EXCEEDED"})
    @ApiSuccess(value = UserSuccessCode.class, codes = {"COLLECTION_CREATED"})
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping
    public SuccessResponse<RestaurantCollectionResponse> create(
            @Valid @RequestBody CreateRestaurantCollectionRequest request) {
        return SuccessResponse.of(UserSuccessCode.COLLECTION_CREATED, restaurantCollectionService.create(request));
    }

    /** 내 컬렉션 목록 — restaurantId를 주면 항목마다 그 식당의 저장 여부(saved)를 함께 내린다(저장·이동 모달용). */
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT", "UNAUTHORIZED", "FORBIDDEN"})
    @GetMapping
    public SuccessResponse<RestaurantCollectionListResponse> getMyCollections(
            @RequestParam(required = false) @Positive Long restaurantId) {
        return SuccessResponse.of(CommonSuccessCode.OK, restaurantCollectionService.getMyCollections(restaurantId));
    }

    /** 컬렉션 상세 헤더 — 공개 컬렉션은 비로그인도 조회. 비공개 컬렉션은 소유자가 아니면 USER-017(403). */
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT"})
    @ApiException(value = UserErrorCode.class, codes = {"COLLECTION_NOT_FOUND", "COLLECTION_PRIVATE"})
    @GetMapping("/{collectionId}")
    public SuccessResponse<RestaurantCollectionResponse> getCollection(
            @PathVariable @Positive Long collectionId) {
        return SuccessResponse.of(CommonSuccessCode.OK, restaurantCollectionService.getCollection(collectionId));
    }

    /** 컬렉션 수정(PATCH) — 보낸 필드만 바꾼다. 공유 전 공개 전환도 visibility로 처리한다. */
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT", "UNAUTHORIZED", "FORBIDDEN"})
    @ApiException(value = UserErrorCode.class, codes = {"COLLECTION_NOT_FOUND", "DUPLICATE_COLLECTION_NAME",
            "UNSUPPORTED_COLLECTION_COLOR", "UNSUPPORTED_COLLECTION_VISIBILITY"})
    @PatchMapping("/{collectionId}")
    public SuccessResponse<RestaurantCollectionResponse> update(
            @PathVariable @Positive Long collectionId,
            @Valid @RequestBody UpdateRestaurantCollectionRequest request) {
        return SuccessResponse.of(CommonSuccessCode.OK, restaurantCollectionService.update(collectionId, request));
    }

    /** 컬렉션 삭제 — 저장 관계만 지우고 식당 원본은 유지한다. */
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT", "UNAUTHORIZED", "FORBIDDEN"})
    @ApiException(value = UserErrorCode.class, codes = {"COLLECTION_NOT_FOUND"})
    @DeleteMapping("/{collectionId}")
    public SuccessResponse<Void> delete(@PathVariable @Positive Long collectionId) {
        restaurantCollectionService.delete(collectionId);
        return SuccessResponse.of(CommonSuccessCode.OK);
    }

    /**
     * 저장 식당 목록 — 커서 페이지네이션. sort는 latest(기본)·rating·review, placeType은 restaurant·cafe·bar(없으면 전체).
     * 공개 컬렉션은 비로그인도 조회할 수 있다.
     */
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT"})
    @ApiException(value = UserErrorCode.class,
            codes = {"COLLECTION_NOT_FOUND", "COLLECTION_PRIVATE", "UNSUPPORTED_SAVED_RESTAURANT_SORT"})
    @GetMapping("/{collectionId}/restaurants")
    public SuccessResponse<SavedRestaurantListResponse> getSavedRestaurants(
            @PathVariable @Positive Long collectionId,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String placeType,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size) {
        return SuccessResponse.of(CommonSuccessCode.OK,
                savedRestaurantQueryService.getSavedRestaurants(collectionId, sort, placeType, cursor, size));
    }

    /** 컬렉션에 식당 저장 — 같은 컬렉션에 이미 있으면 USER-012. 응답은 갱신된 컬렉션 헤더(저장 수·커버). */
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT", "UNAUTHORIZED", "FORBIDDEN"})
    @ApiException(value = UserErrorCode.class, codes = {"COLLECTION_NOT_FOUND", "RESTAURANT_NOT_FOUND",
            "RESTAURANT_ALREADY_SAVED", "SAVED_RESTAURANT_LIMIT_EXCEEDED"})
    @ApiSuccess(value = UserSuccessCode.class, codes = {"RESTAURANT_SAVED"})
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/{collectionId}/restaurants")
    public SuccessResponse<RestaurantCollectionResponse> saveRestaurant(
            @PathVariable @Positive Long collectionId,
            @Valid @RequestBody SaveRestaurantRequest request) {
        return SuccessResponse.of(UserSuccessCode.RESTAURANT_SAVED,
                restaurantCollectionService.saveRestaurant(collectionId, request));
    }

    /** 선택 식당 제거 — restaurantIds 중 하나라도 컬렉션에 없으면 USER-013으로 전체 거부한다. */
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT", "UNAUTHORIZED", "FORBIDDEN"})
    @ApiException(value = UserErrorCode.class, codes = {"COLLECTION_NOT_FOUND", "RESTAURANT_NOT_SAVED"})
    @DeleteMapping("/{collectionId}/restaurants")
    public SuccessResponse<RestaurantCollectionResponse> removeRestaurants(
            @PathVariable @Positive Long collectionId,
            @RequestParam List<Long> restaurantIds) {
        return SuccessResponse.of(CommonSuccessCode.OK,
                restaurantCollectionService.removeRestaurants(collectionId, restaurantIds));
    }

    /** 선택 식당 이동(action 서브리소스) — 대상 컬렉션에 이미 있는 식당이 하나라도 있으면 USER-012로 전체 실패한다. */
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT", "UNAUTHORIZED", "FORBIDDEN"})
    @ApiException(value = UserErrorCode.class, codes = {"COLLECTION_NOT_FOUND", "RESTAURANT_NOT_SAVED",
            "RESTAURANT_ALREADY_SAVED", "SAME_COLLECTION_MOVE", "SAVED_RESTAURANT_LIMIT_EXCEEDED"})
    @PostMapping("/{collectionId}/restaurants/move")
    public SuccessResponse<MoveSavedRestaurantsResponse> moveRestaurants(
            @PathVariable @Positive Long collectionId,
            @Valid @RequestBody MoveSavedRestaurantsRequest request) {
        return SuccessResponse.of(CommonSuccessCode.OK,
                restaurantCollectionService.moveRestaurants(collectionId, request));
    }
}
