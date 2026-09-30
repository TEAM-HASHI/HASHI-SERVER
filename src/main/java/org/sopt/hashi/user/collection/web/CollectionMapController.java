package org.sopt.hashi.user.collection.web;

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
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT", "CONFLICT"})
    @ApiException(value = UserErrorCode.class, codes = {"COLLECTION_NOT_FOUND", "COLLECTION_MAP_UNAVAILABLE"})
    @ApiSuccess(value = CommonSuccessCode.class, codes = {"OK"})
    @GetMapping("/api/v1/collections/{collectionId}/map-markers")
    public ResponseEntity<SuccessResponse<CollectionMapMarkersResponse>> getMarkers(
            @Positive @PathVariable Long collectionId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(SuccessResponse.of(CommonSuccessCode.OK, service.getMarkers(collectionId)));
    }
}
