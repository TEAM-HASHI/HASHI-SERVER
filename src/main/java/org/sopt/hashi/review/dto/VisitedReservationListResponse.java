package org.sopt.hashi.review.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.util.List;

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
            LocalDateTime visitedAt,
            int adultCount,
            int childCount,
            boolean reviewed,
            Long reviewId,
            Integer rating,
            Long earnedPoint
    ) {
    }
}
