package org.sopt.hashi.user.collection.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.Positive;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.shared.error.CommonSuccessCode;
import org.sopt.hashi.shared.response.SuccessResponse;
import org.sopt.hashi.shared.swagger.ApiException;
import org.sopt.hashi.shared.swagger.ApiSuccess;
import org.sopt.hashi.user.code.UserErrorCode;
import org.sopt.hashi.user.collection.dto.CollectionMapMarkersResponse;
import org.sopt.hashi.user.collection.service.CollectionMapQueryService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
public class CollectionMapController {
    private final CollectionMapQueryService service;

    public CollectionMapController(CollectionMapQueryService service) {
        this.service = service;
    }

    /** 공개는 누구나, 비공개는 소유 USER만. ID순 전체 유효 핀을 반환하며 목록 페이지/필터는 적용하지 않는다. */
    @Operation(summary = "컬렉션의 전체 지도 핀 조회", description = """
            공개 컬렉션은 로그인 없이 조회할 수 있고, 비공개 컬렉션은 소유 USER만 조회할 수 있습니다.
            익명 사용자·다른 USER·ADMIN이 비공개 컬렉션을 요청하면 존재를 숨기기 위해 USER-006(404)을 반환합니다.
            viewport, BBOX, 목록 페이지, 정렬·필터와 무관하게 컬렉션 전체에서 현재 유효한 위치 핀을 식당 ID순으로 반환합니다.
            현재 컬렉션 저장 상한은 1,000개이며 응답도 페이지네이션 없이 전체를 반환합니다.
            좌표가 없거나 만료된 식당은 content에서만 제외되며 저장 관계는 유지되고 locationUnavailableCount에 포함됩니다.
            삭제·미존재 식당은 visibleRestaurantCount와 content에서 제외됩니다. 응답은 no-store입니다.
            """)
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT", "CONFLICT"})
    @ApiException(value = UserErrorCode.class, codes = {"COLLECTION_NOT_FOUND", "COLLECTION_MAP_UNAVAILABLE"})
    @ApiSuccess(value = CommonSuccessCode.class, codes = {"OK"})
    @GetMapping("/api/v1/collections/{collectionId}/map-markers")
    public ResponseEntity<SuccessResponse<CollectionMapMarkersResponse>> getMarkers(
            @Parameter(description = "조회할 컬렉션 ID", example = "42")
            @Positive @PathVariable Long collectionId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(SuccessResponse.of(CommonSuccessCode.OK, service.getMarkers(collectionId)));
    }
}
