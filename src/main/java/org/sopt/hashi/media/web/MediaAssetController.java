package org.sopt.hashi.media.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.sopt.hashi.media.code.MediaErrorCode;
import org.sopt.hashi.media.dto.CompleteMediaAssetsRequest;
import org.sopt.hashi.media.dto.CreateMediaAssetsRequest;
import org.sopt.hashi.media.dto.CreateMediaAssetsResponse;
import org.sopt.hashi.media.dto.MediaAssetStatusesResponse;
import org.sopt.hashi.media.service.MediaAssetService;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.shared.error.CommonSuccessCode;
import org.sopt.hashi.shared.response.SuccessResponse;
import org.sopt.hashi.shared.swagger.ApiException;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/media/assets")
public class MediaAssetController {

    private final MediaAssetService mediaAssetService;

    public MediaAssetController(MediaAssetService mediaAssetService) {
        this.mediaAssetService = mediaAssetService;
    }

    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT", "UNAUTHORIZED"})
    @ApiException(value = MediaErrorCode.class, codes = {
            "PURPOSE_FORBIDDEN",
            "PIPELINE_UNAVAILABLE",
            "UNSUPPORTED_FILE_TYPE",
            "FILE_SIZE_EXCEEDED"
    })
    @PostMapping
    public SuccessResponse<CreateMediaAssetsResponse> createAssets(
            @Valid @RequestBody CreateMediaAssetsRequest request) {
        return SuccessResponse.of(CommonSuccessCode.OK, mediaAssetService.createAssets(request));
    }

    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT", "UNAUTHORIZED"})
    @ApiException(value = MediaErrorCode.class, codes = {
            "ASSET_NOT_FOUND",
            "UPLOAD_NOT_FOUND",
            "UPLOAD_METADATA_MISMATCH",
            "ASSET_EXPIRED",
            "INVALID_STATE",
            "DUPLICATE_ASSET",
            "PIPELINE_UNAVAILABLE"
    })
    @PostMapping("/complete")
    public SuccessResponse<MediaAssetStatusesResponse> completeAssets(
            @Valid @RequestBody CompleteMediaAssetsRequest request) {
        return SuccessResponse.of(CommonSuccessCode.OK, mediaAssetService.completeAssets(request));
    }

    @ApiException(value = CommonErrorCode.class, codes = {"INVALID_INPUT", "UNAUTHORIZED"})
    @ApiException(value = MediaErrorCode.class, codes = {"ASSET_NOT_FOUND", "DUPLICATE_ASSET"})
    @GetMapping
    public SuccessResponse<MediaAssetStatusesResponse> getAssetStatuses(
            @RequestParam
            @NotEmpty(message = "assetIds는 최소 1개 이상이어야 합니다.")
            @Size(max = 10, message = "assetIds는 최대 10개까지 요청할 수 있습니다.")
            List<UUID> assetIds) {
        return SuccessResponse.of(CommonSuccessCode.OK, mediaAssetService.getAssetStatuses(assetIds));
    }
}
