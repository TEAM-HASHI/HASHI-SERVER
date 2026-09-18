package org.sopt.hashi.review.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import org.sopt.hashi.media.MediaImage;
import org.sopt.hashi.review.domain.ReviewKeyword;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReviewContextResponse(
        Long reservationId,
        Long restaurantId,
        String restaurantName,
        String restaurantThumbnailUrl,
        MediaImage restaurantThumbnailImage,
        LocalDateTime visitedAt,
        int adultCount,
        int teenCount,
        int childCount,
        boolean reviewable,
        ReviewUnavailableReason reviewUnavailableReason,
        List<ReviewKeywordOption> reviewKeywordOptions
) {

    public static List<ReviewKeywordOption> keywordOptions() {
        return Arrays.stream(ReviewKeyword.values())
                .map(ReviewKeywordOption::from)
                .toList();
    }

    public record ReviewKeywordOption(String code, String label) {

        private static ReviewKeywordOption from(ReviewKeyword keyword) {
            return new ReviewKeywordOption(keyword.name(), keyword.getLabel());
        }
    }
}
