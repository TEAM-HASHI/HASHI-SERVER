package org.sopt.hashi.user.collection.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.user.collection.domain.SavedRestaurantSort;

/**
 * 저장 식당 목록 커서 — 식당 목록({@code RestaurantCursorCodec})과 같은 규격(Base64url, {@code SORT|값|id}).
 * 최신순은 저장 행 id, 별점순·리뷰순은 정렬값과 식당 id로 동점을 가른다. 정렬이 바뀐 커서는 잘못된 입력으로 거부한다.
 */
final class SavedRestaurantCursorCodec {

    private static final String DELIMITER = "|";

    private SavedRestaurantCursorCodec() {
    }

    /** 커서 위치 — 최신순은 savedId만, 별점순은 rating·restaurantId, 리뷰순은 reviewCount·restaurantId를 쓴다. */
    record Cursor(SavedRestaurantSort sort, BigDecimal rating, Long reviewCount, Long id) {
    }

    static Cursor decode(String cursor, SavedRestaurantSort requestedSort) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = decoded.split(Pattern.quote(DELIMITER), -1);
            if (parts.length != 3) {
                throw new IllegalArgumentException("Invalid cursor format");
            }
            SavedRestaurantSort cursorSort = SavedRestaurantSort.valueOf(parts[0]);
            if (cursorSort != requestedSort) {
                throw new IllegalArgumentException("Cursor sort does not match requested sort");
            }
            long id = Long.parseLong(parts[2]);
            if (id <= 0) {
                throw new IllegalArgumentException("Cursor id must be positive");
            }
            return switch (cursorSort) {
                case LATEST -> new Cursor(cursorSort, null, null, id);
                case RATING -> new Cursor(cursorSort, new BigDecimal(parts[1]), null, id);
                case REVIEW -> new Cursor(cursorSort, null, Long.parseLong(parts[1]), id);
            };
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, exception);
        }
    }

    static String encode(SavedRestaurantSort sort, SavedRestaurantItem item) {
        String value = switch (sort) {
            case LATEST -> "";
            case RATING -> item.rating().toPlainString();
            case REVIEW -> Long.toString(item.reviewCount());
        };
        long id = sort == SavedRestaurantSort.LATEST ? item.savedId() : item.restaurantId();
        String rawCursor = sort.name() + DELIMITER + value + DELIMITER + id;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(rawCursor.getBytes(StandardCharsets.UTF_8));
    }
}
