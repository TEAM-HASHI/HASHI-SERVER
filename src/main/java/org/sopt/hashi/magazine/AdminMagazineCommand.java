package org.sopt.hashi.magazine;

import java.util.List;
import java.util.UUID;

/**
 * 어드민 진입점이 magazine 모듈로 전달하는 등록·부분 수정 명령.
 * 상세 화면 데이터(content·cardNews·hashtags·restaurantIds)는 null이면 변경하지 않고,
 * 값을 보내면 전체 교체한다(빈 목록은 모두 지움). 목록의 순서가 곧 노출 순서다.
 */
public record AdminMagazineCommand(
        String title,
        ImageCommand bannerImage,
        ImageCommand thumbnailImage,
        String instagramRedirectUrl,
        String content,
        List<ImageCommand> cardNews,
        List<String> hashtags,
        List<Long> restaurantIds
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
