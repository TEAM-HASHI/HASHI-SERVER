package org.sopt.hashi.review.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.sopt.hashi.media.ImageReference;
import org.sopt.hashi.media.MediaImage;
import org.sopt.hashi.media.MediaImageRequest;
import org.sopt.hashi.media.MediaImageRole;
import org.sopt.hashi.media.MediaImageSelection;
import org.sopt.hashi.review.domain.ReviewImage;
import org.sopt.hashi.review.dto.ReviewImageResponse;
import org.sopt.hashi.shared.storage.FileStorage;

/** 호출 서비스가 bulk 조회한 결과를 순서가 유지되는 리뷰 슬롯으로 변환한다. */
final class ReviewImageProjection {
    private ReviewImageProjection() { }

    static List<MediaImageRequest> requests(Collection<ReviewImage> images, MediaImageRole role) {
        return images.stream().map(ReviewImage::getImageAssetId).filter(Objects::nonNull)
                .map(assetId -> new MediaImageRequest(assetId, role)).distinct().toList();
    }

    static List<ReviewImageResponse> project(List<ReviewImage> images, MediaImageRole role,
                                            Map<MediaImageRequest, MediaImage> loaded, FileStorage storage) {
        return images.stream().map(image -> {
            String legacyUrl = image.getImageAssetId() == null
                    ? storage.resolveFileUrl(image.getFileKey()) : null;
            MediaImage rendition = image.getImageAssetId() == null ? null
                    : loaded.get(new MediaImageRequest(image.getImageAssetId(), role));
            var selection = MediaImageSelection.from(
                    new ImageReference(image.getImageAssetId(), legacyUrl), rendition);
            return new ReviewImageResponse(image.getId(), image.getDisplayOrder(),
                    selection.image(), selection.legacyUrl());
        }).toList();
    }

    static List<String> urls(List<ReviewImageResponse> images) {
        return images.stream().map(ReviewImageResponse::displayUrl).filter(Objects::nonNull).toList();
    }
}
