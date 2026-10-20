package org.sopt.hashi.review.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Positive;
import org.sopt.hashi.review.code.ReviewErrorCode;
import org.sopt.hashi.review.domain.RestaurantPhotoFilter;
import org.sopt.hashi.review.dto.RestaurantPhotosResponse;
import org.sopt.hashi.review.service.RestaurantPhotoService;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.shared.error.CommonSuccessCode;
import org.sopt.hashi.shared.response.SuccessResponse;
import org.sopt.hashi.shared.swagger.ApiException;
import org.sopt.hashi.shared.swagger.ApiSuccess;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
public class RestaurantPhotoController {
    private final RestaurantPhotoService service;

    public RestaurantPhotoController(RestaurantPhotoService service) {
        this.service = service;
    }

    @Operation(summary = "식당 사진 탭 조회", description = """
            비회원도 조회할 수 있습니다. 최초 진입은 filter=ALL이고 cursor를 생략하며 항상 최대 20장을 반환합니다.
            다음 조회는 응답의 nextCursor를 cursor로 보내며 hasNext=false이면 중단합니다. size는 지정하지 않습니다.
            예: /api/v1/restaurants/123/photos?filter=REVIEW&cursor={nextCursor}
            ALL=전체, REPRESENTATIVE=대표사진, MENU=메뉴사진, REVIEW=리뷰사진입니다. 동영상은 포함하지 않습니다.
            대표사진은 등록 표시 순서, 메뉴사진은 등록 ID 순서, 리뷰사진은 최신 리뷰·사진 표시 순서입니다.
            ALL은 동일 원본 참조(asset ID 또는 원본 파일 키)를 한 번만 노출하며 대표사진 1장·리뷰사진 3장을 반복합니다.
            한 출처가 끝나면 나머지를 이어서 표시하고, 마지막에 메뉴사진을 표시합니다. 이미지 내용의 유사도 비교는 하지 않습니다.
            counts는 모든 필터 칩의 개수, totalCount는 선택한 필터의 개수입니다. ALL은 중복 제거 후 개수입니다.
            빈 목록도 200이며 photos=[], hasNext=false, nextCursor=null입니다. 없는/삭제된 식당은 RESTAURANT-004입니다.
            필터 변경·리뷰 작성 후 복귀 시 cursor를 생략하고 새로 조회하세요. 페이지 간 데이터 스냅샷은 고정하지 않습니다.
            커서는 식당·필터에 종속되며 변경한 조건에 재사용하거나 형식이 잘못되면 400입니다.
            마지막 정렬 키 이후부터 조회합니다. 삭제로 앞 페이지가 줄어도 건너뛰지 않습니다.
            사진 순서 수정·동일 원본의 출처 변경 중에는 목록이 달라질 수 있습니다. 서버가 캐시하지 않으며 매 요청 현재 삭제 상태를 반영합니다.
            asset 이미지는 원본 비율의 PHOTO_DETAIL 파생본을 사용합니다. 처리 중·실패·구규격 미지원이면
            image.status(PROCESSING/FAILED/UNAVAILABLE)와 imageUrl=null을 보고 기본 이미지를 표시합니다.
            UNAVAILABLE은 재처리 전까지 사용할 파생본이 없다는 뜻이며 자동으로 준비 중이라는 뜻은 아닙니다.
            legacy 사진은 image=null이고 imageUrl을 사용합니다. 상세보기는 같은 필터의 목록과 다음 페이지를 재사용합니다.
            하단 저장 수는 GET /api/v1/restaurants/save-counts, 내 저장 여부는 GET /api/v1/users/me/restaurant-saves를 사용합니다.
            """)
    @ApiSuccess(value = CommonSuccessCode.class, codes = {"OK"})
    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT"})
    @ApiException(value = ReviewErrorCode.class, codes = {"RESTAURANT_NOT_FOUND"})
    @GetMapping("/api/v1/restaurants/{restaurantId}/photos")
    public ResponseEntity<SuccessResponse<RestaurantPhotosResponse>> getPhotos(
            @Positive @PathVariable long restaurantId,
            @Parameter(description = "대문자 필터 코드. 생략하면 ALL")
            @RequestParam(defaultValue = "ALL") RestaurantPhotoFilter filter,
            @Parameter(description = "첫 요청은 생략. 이후 응답의 nextCursor를 그대로 전달하며 식당·필터가 바뀌면 생략")
            @Size(max = 512) @RequestParam(required = false) String cursor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(SuccessResponse.of(CommonSuccessCode.OK, service.getPhotos(restaurantId, filter, cursor)));
    }
}
