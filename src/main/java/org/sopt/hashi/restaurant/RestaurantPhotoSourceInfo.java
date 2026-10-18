package org.sopt.hashi.restaurant;

import java.util.UUID;

/** 사진 탭 조합용 참조. 원본 키는 동일 사진 판별에만 쓰고 API에 노출하지 않는다. */
public record RestaurantPhotoSourceInfo(Long id, String fileKey, UUID assetId) {
}
