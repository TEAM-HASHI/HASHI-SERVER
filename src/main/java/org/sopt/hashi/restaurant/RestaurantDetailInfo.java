package org.sopt.hashi.restaurant;

import java.math.BigDecimal;
import java.util.List;
import org.sopt.hashi.media.ImageReference;

/**
 * 모듈 간 전달용 식당 상세 DTO. 요약({@link RestaurantInfo}: id·name·address·대표 이미지)보다 많은 표시 정보가
 * 필요한 조회(예약 상세, 매거진 연결 식당 카드)에 쓴다. 요약만 필요한 목록은 이 타입을 받지 않는다 —
 * 영업시간까지 함께 읽어 조회 비용이 더 들기 때문이다.
 * 대표 이미지는 object key가 아닌 전환기 {@link ImageReference}로 전달한다. 대표 이미지가 없으면 null.
 * {@code imageUrls}는 식당 이미지 전체를 노출 순서대로 담은 조회 URL이다(coding-style §4-2). 이미지가 없으면 빈 목록이다.
 * {@code todayBusinessHour}는 일본 시각 기준 오늘 요일의 영업시간이며 등록된 요일이 없으면 null이다.
 */
public record RestaurantDetailInfo(
        Long id,
        String name,
        String nameJa,
        String address,
        String area,
        String foodCategory,
        ImageReference thumbnailImageReference,
        List<String> imageUrls,
        BigDecimal rating,
        TodayBusinessHourInfo todayBusinessHour,
        PriceRangeInfo priceRange) {

    /** 오늘 영업시간 — 시각은 {@code HH:mm} 문자열, 휴무일이면 시각이 null이고 {@code closed}가 true다. */
    public record TodayBusinessHourInfo(
            String date,
            String dayOfWeek,
            String openTime,
            String closeTime,
            boolean closed) {
    }

    /** 예상 가격대 — 통화 코드(ISO 4217)와 정수 금액. */
    public record PriceRangeInfo(
            String currency,
            Long minPrice,
            Long maxPrice) {
    }
}
