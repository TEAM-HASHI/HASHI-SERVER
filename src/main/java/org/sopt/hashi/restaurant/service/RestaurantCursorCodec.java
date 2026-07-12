package org.sopt.hashi.restaurant.service;

import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.Base64;
import java.util.regex.Pattern;
import org.sopt.hashi.restaurant.domain.Restaurant;
import org.sopt.hashi.restaurant.domain.RestaurantCursor;
import org.sopt.hashi.restaurant.domain.RestaurantSort;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;

final class RestaurantCursorCodec {

    private static final String DELIMITER = "|";

    private RestaurantCursorCodec() {
    }

    static RestaurantCursor decode(String cursor, RestaurantSort requestedSort) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }

        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = decoded.split(Pattern.quote(DELIMITER), -1);
            if (parts.length != 4) {
                throw new IllegalArgumentException("Invalid cursor format");
            }

            RestaurantSort cursorSort = RestaurantSort.valueOf(parts[0]);
            if (cursorSort != requestedSort) {
                throw new IllegalArgumentException("Cursor sort does not match requested sort");
            }

            Long id = parsePositiveLong(parts[3]);
            return switch (cursorSort) {
                case BASIC -> new RestaurantCursor(cursorSort, null, null, id);
                case POPULAR -> new RestaurantCursor(
                        cursorSort,
                        parseDecimal(parts[2]),
                        parseLong(parts[1]),
                        id);
                case RATING -> new RestaurantCursor(cursorSort, parseDecimal(parts[2]), null, id);
            };
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, exception);
        }
    }

    static String encode(Restaurant restaurant, RestaurantSort sort) {
        String reviewCount = sort == RestaurantSort.POPULAR
                ? Long.toString(restaurant.getReviewCount())
                : "";
        String rating = sort == RestaurantSort.BASIC
                ? ""
                : restaurant.getRating().toPlainString();
        String rawCursor = sort.name() + DELIMITER + reviewCount + DELIMITER + rating
                + DELIMITER + restaurant.getId();

        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(rawCursor.getBytes(StandardCharsets.UTF_8));
    }

    private static Long parseLong(String value) {
        return Long.parseLong(value);
    }

    private static Long parsePositiveLong(String value) {
        long parsed = Long.parseLong(value);
        if (parsed <= 0) {
            throw new IllegalArgumentException("Cursor id must be positive");
        }
        return parsed;
    }

    private static BigDecimal parseDecimal(String value) {
        return new BigDecimal(value);
    }
}
