package org.sopt.hashi.review.service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import org.sopt.hashi.review.domain.RestaurantPhotoFilter;
import org.sopt.hashi.review.domain.ReviewPhotoRepository;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;

/** 각 출처의 마지막 정렬 키와 ALL 혼합 순서만 보관한다. offset이나 사진 URL은 넣지 않는다. */
final class RestaurantPhotoCursorCodec {
    private RestaurantPhotoCursorCodec() {
    }

    record Cursor(int representativeOrder, long representativeId, long menuId,
                  ReviewPhotoRepository.Position review, int phase, boolean menuStarted) {
        static Cursor first() {
            return new Cursor(0, 0, 0, null, 0, false);
        }
    }

    static Cursor decode(String token, long restaurantId, RestaurantPhotoFilter filter) {
        if (token == null) {
            return Cursor.first();
        }
        try {
            if (token.isBlank() || token.length() > 512) {
                throw new IllegalArgumentException("Invalid cursor length");
            }
            String[] values = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8)
                    .split("\\|", -1);
            if (values.length != 12 || !"1".equals(values[0])
                    || Long.parseLong(values[1]) != restaurantId || !filter.name().equals(values[2])) {
                throw new IllegalArgumentException("Cursor does not match request");
            }
            int order = Integer.parseInt(values[3]);
            long representativeId = Long.parseLong(values[4]);
            long menuId = Long.parseLong(values[5]);
            int phase = Integer.parseInt(values[10]);
            if (order < 0 || representativeId < 0 || menuId < 0 || phase < 0 || phase > 3
                    || (order == 0) != (representativeId == 0)
                    || (!"0".equals(values[11]) && !"1".equals(values[11]))) {
                throw new IllegalArgumentException("Invalid cursor position");
            }
            ReviewPhotoRepository.Position review = null;
            if (!values[6].isEmpty()) {
                review = new ReviewPhotoRepository.Position("~".equals(values[6])
                        ? null : LocalDateTime.parse(values[6]),
                        Long.parseLong(values[7]), Integer.parseInt(values[8]), Long.parseLong(values[9]));
                if (review.reviewId() < 1 || review.imageId() < 1 || review.displayOrder() < 0
                        || (review.createdAt() != null
                        && (review.createdAt().getYear() < 1000 || review.createdAt().getYear() > 9999))) {
                    throw new IllegalArgumentException("Invalid review position");
                }
            } else if (!values[7].isEmpty() || !values[8].isEmpty() || !values[9].isEmpty()) {
                throw new IllegalArgumentException("Incomplete review position");
            }
            return new Cursor(order, representativeId, menuId, review, phase, "1".equals(values[11]));
        } catch (RuntimeException exception) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, exception);
        }
    }

    static String encode(Cursor cursor, long restaurantId, RestaurantPhotoFilter filter) {
        ReviewPhotoRepository.Position review = cursor.review();
        String raw = String.join("|", "1", Long.toString(restaurantId), filter.name(),
                Integer.toString(cursor.representativeOrder()), Long.toString(cursor.representativeId()),
                Long.toString(cursor.menuId()),
                review == null ? "" : review.createdAt() == null ? "~" : review.createdAt().toString(),
                review == null ? "" : Long.toString(review.reviewId()),
                review == null ? "" : Integer.toString(review.displayOrder()),
                review == null ? "" : Long.toString(review.imageId()), Integer.toString(cursor.phase()),
                cursor.menuStarted() ? "1" : "0");
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
