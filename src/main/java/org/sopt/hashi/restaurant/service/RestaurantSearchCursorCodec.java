package org.sopt.hashi.restaurant.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.sopt.hashi.restaurant.domain.RestaurantSearchQuery;
import org.sopt.hashi.restaurant.domain.RestaurantSort;
import org.sopt.hashi.restaurant.domain.RestaurantGenre;
import org.sopt.hashi.restaurant.domain.RestaurantListType;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;

/** 검색 조건과 정렬 경계값을 묶는다. 스냅샷이나 접근 권한을 나타내는 토큰은 아니다. */
final class RestaurantSearchCursorCodec {

    private RestaurantSearchCursorCodec() {
    }

    static String fingerprint(RestaurantSearchQuery query, RestaurantGenre genre, RestaurantListType type,
                              RestaurantSort sort) {
        String condition = query.hashtagOnly() + "|" + String.join(" ", query.terms())
                + "|" + genre + "|" + type + "|" + sort;
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
                    .digest(condition.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static String encode(List<BigDecimal> values, String fingerprint) {
        String raw = "S1|" + fingerprint + "|" + values.stream().map(BigDecimal::toPlainString)
                .collect(Collectors.joining("|"));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static List<BigDecimal> decode(String token, String fingerprint, RestaurantSort sort, int termCount) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            if (token.length() > 200) {
                throw new IllegalArgumentException();
            }
            String[] parts = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8).split("\\|", -1);
            int valueCount = sort == RestaurantSort.BASIC ? 5 : 3;
            if (parts.length != valueCount + 2 || !parts[0].equals("S1") || !parts[1].equals(fingerprint)) {
                throw new IllegalArgumentException();
            }
            List<BigDecimal> values = Arrays.stream(parts).skip(2).map(value -> {
                if (!value.matches("[0-9]{1,19}(\\.[0-9])?")) {
                    throw new IllegalArgumentException();
                }
                return new BigDecimal(value);
            }).toList();
            if (values.getLast().longValueExact() <= 0) {
                throw new IllegalArgumentException();
            }
            if (sort == RestaurantSort.BASIC) {
                for (int i = 0; i < 4; i++) {
                    int value = values.get(i).intValueExact();
                    if (value < 0 || value > termCount) {
                        throw new IllegalArgumentException();
                    }
                }
                if (values.getFirst().signum() == 0) {
                    throw new IllegalArgumentException();
                }
                for (int i = 1; i < 4; i++) {
                    if (values.get(i).compareTo(values.getFirst()) > 0) {
                        throw new IllegalArgumentException();
                    }
                }
            } else {
                BigDecimal rating = values.get(sort == RestaurantSort.RATING ? 0 : 1);
                if (rating.compareTo(BigDecimal.valueOf(5)) > 0) {
                    throw new IllegalArgumentException();
                }
                values.get(sort == RestaurantSort.RATING ? 1 : 0).longValueExact();
            }
            return values;
        } catch (IllegalArgumentException | ArithmeticException exception) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, exception);
        }
    }
}
