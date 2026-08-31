package org.sopt.hashi.magazine.dto;

import java.time.LocalDateTime;
import java.util.List;
import org.sopt.hashi.media.MediaImage;

/**
 * 매거진 목록 응답(최신순 커서 페이지네이션). nextCursor는 다음 페이지 요청에 그대로 전달하며,
 * hasNext가 false면 마지막 페이지라 nextCursor는 null이다.
 */
public record MagazineListResponse(
        List<MagazineSummaryResponse> magazines,
        Long nextCursor,
        boolean hasNext) {

    public record MagazineSummaryResponse(
            Long magazineId,
            String title,
            String bannerImageUrl,
            MediaImage bannerImage,
            String thumbnailImageUrl,
            MediaImage thumbnailImage,
            String instagramRedirectUrl,
            LocalDateTime createdAt) {
    }
}
