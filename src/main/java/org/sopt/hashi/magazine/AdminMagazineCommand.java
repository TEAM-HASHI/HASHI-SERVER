package org.sopt.hashi.magazine;

import java.util.UUID;

/** 어드민 진입점이 magazine 모듈로 전달하는 등록·부분 수정 명령. */
public record AdminMagazineCommand(
        String title,
        ImageCommand bannerImage,
        ImageCommand thumbnailImage,
        String instagramRedirectUrl
) {

    /** legacy key와 신규 public asset ID 중 정확히 하나를 가진 이미지 교체 명령. */
    public record ImageCommand(String imageKey, UUID imageAssetId) {

        public ImageCommand {
            if ((imageKey == null) == (imageAssetId == null)
                    || (imageKey != null && imageKey.isBlank())) {
                throw new IllegalArgumentException(
                        "image command requires exactly one non-blank source");
            }
        }
    }
}
