package org.sopt.hashi.review.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.sopt.hashi.media.MediaAssetPurpose;
import org.sopt.hashi.media.MediaAssetUse;
import org.sopt.hashi.review.domain.ReviewImage;
import org.sopt.hashi.review.dto.UpdateReviewRequest;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;

/** 잠긴 리뷰의 현재 association과 요청을 비교한다. 실제 저장 및 media 전이는 호출 서비스가 담당한다. */
record ReviewImageWritePlan(List<ReviewImage> images, List<MediaAssetUse> claims,
                           List<MediaAssetUse> retires) {

    static ReviewImageWritePlan create(List<String> keys, List<UUID> assetIds) {
        if (keys != null && assetIds != null) {
            throw invalid();
        }
        if (assetIds != null) {
            if (assetIds.size() > 10 || assetIds.stream().anyMatch(Objects::isNull)
                    || new HashSet<>(assetIds).size() != assetIds.size()) {
                throw invalid();
            }
            return new ReviewImageWritePlan(IntStream.range(0, assetIds.size())
                    .mapToObj(index -> ReviewImage.createAsset(assetIds.get(index), index)).toList(),
                    assetIds.stream().map(id -> new MediaAssetUse(id, MediaAssetPurpose.REVIEW)).toList(),
                    List.of());
        }
        if (keys == null) {
            return new ReviewImageWritePlan(List.of(), List.of(), List.of());
        }
        validateKeys(keys, false);
        return new ReviewImageWritePlan(IntStream.range(0, keys.size())
                .mapToObj(index -> ReviewImage.create(validateNewKey(keys.get(index)), index)).toList(),
                List.of(), List.of());
    }

    static ReviewImageWritePlan update(List<ReviewImage> existing, UpdateReviewRequest request) {
        validateUpdateInput(request);
        List<ReviewImage> result = new ArrayList<>();
        List<MediaAssetUse> claims = new ArrayList<>();
        if (request.images() != null) {
            Map<Long, ReviewImage> byId = new HashMap<>();
            existing.stream().filter(image -> image.getId() != null)
                    .forEach(image -> byId.put(image.getId(), image));
            for (UpdateReviewRequest.Image item : request.images()) {
                if (item.reviewImageId() != null) {
                    ReviewImage retained = byId.remove(item.reviewImageId());
                    if (retained == null) {
                        throw invalid();
                    }
                    result.add(retained);
                } else {
                    result.add(ReviewImage.createAsset(item.imageAssetId(), result.size()));
                    // 생성과 달리 교체/추가로 전달한 새 이미지는 READY 후 연결한다.
                    claims.add(new MediaAssetUse(item.imageAssetId(), MediaAssetPurpose.REVIEW, true));
                }
            }
        } else {
            // 구버전 클라이언트에는 key 없는 신규 asset 슬롯이 보이지 않아 의도치 않게 제거할 수 있다.
            // 그런 리뷰는 retained ID를 명시할 수 있는 images 계약으로만 수정한다.
            if (existing.stream().anyMatch(image -> image.getImageAssetId() != null && image.getFileKey() == null)) {
                throw invalid();
            }
            Map<String, ArrayDeque<ReviewImage>> byKey = new HashMap<>();
            existing.stream().filter(image -> image.getFileKey() != null).forEach(image ->
                    byKey.computeIfAbsent(image.getFileKey(), ignored -> new ArrayDeque<>()).add(image));
            for (String key : request.imageFileKeys()) {
                ArrayDeque<ReviewImage> candidates = byKey.get(key);
                ReviewImage retained = candidates == null ? null : candidates.pollFirst();
                result.add(retained == null ? ReviewImage.create(validateNewKey(key), result.size()) : retained);
            }
        }
        List<MediaAssetUse> retires = existing.stream().filter(image -> !result.contains(image))
                .map(ReviewImage::getImageAssetId).filter(Objects::nonNull)
                .map(id -> new MediaAssetUse(id, MediaAssetPurpose.REVIEW)).toList();
        return new ReviewImageWritePlan(List.copyOf(result), List.copyOf(claims), retires);
    }

    static void validateUpdateInput(UpdateReviewRequest request) {
        if (!request.isImageSourceValid()) {
            throw invalid();
        }
        if (request.images() != null) {
            if (request.images().size() > 10) {
                throw invalid();
            }
            Set<Long> retainedIds = new HashSet<>();
            Set<UUID> newAssets = new HashSet<>();
            for (UpdateReviewRequest.Image item : request.images()) {
                if (item == null || !item.isSourceValid()) {
                    throw invalid();
                }
                if (item.reviewImageId() != null) {
                    if (item.reviewImageId() < 1 || !retainedIds.add(item.reviewImageId())) {
                        throw invalid();
                    }
                } else {
                    if (!newAssets.add(item.imageAssetId())) {
                        throw invalid();
                    }
                }
            }
        } else {
            validateKeys(request.imageFileKeys(), true);
        }
    }

    private static void validateKeys(List<String> keys, boolean distinct) {
        if (keys == null || keys.size() > 10
                || keys.stream().anyMatch(key -> key == null || key.isBlank() || key.length() > 500)
                || (distinct && new HashSet<>(keys).size() != keys.size())) {
            throw invalid();
        }
    }

    private static String validateNewKey(String key) {
        if (!key.startsWith("uploads/reviews/") || key.contains("..") || key.contains("\\")) {
            throw invalid();
        }
        return key;
    }

    private static BusinessException invalid() {
        return new BusinessException(CommonErrorCode.INVALID_INPUT);
    }
}
