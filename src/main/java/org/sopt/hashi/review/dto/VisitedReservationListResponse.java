package org.sopt.hashi.review.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.util.List;
import org.sopt.hashi.media.MediaImage;
import org.sopt.hashi.review.domain.ReviewLifecycleStatus;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record VisitedReservationListResponse(
        List<VisitedReservationResponse> content,
        long totalCount,
        Long nextCursor,
        boolean hasNext
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VisitedReservationResponse(
            Long reservationId,
            Long restaurantId,
            String restaurantName,
            String restaurantThumbnailUrl,
            MediaImage restaurantThumbnailImage,
            LocalDateTime visitedAt,
            int adultCount,
            int teenCount,
            int childCount,
            ReviewLifecycleStatus reviewStatus,
            boolean reviewable,
            ReviewUnavailableReason reviewUnavailableReason,
            Long reviewId,
            Integer rating,
            Long earnedPoint
    ) {
    }
}
