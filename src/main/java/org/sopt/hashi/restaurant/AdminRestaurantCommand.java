package org.sopt.hashi.restaurant;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

/**
 * 어드민 식당 등록·수정 커맨드 — 진입점(admin)이 {@link RestaurantPort}로 넘기는 계약.
 * 등록 시 필수 값 검증은 admin 요청 DTO(Bean Validation)가 담당하고, 수정(PATCH) 시 null 필드는
 * 변경하지 않는다. genre·curationTypes는 사용자 API와 같은 소문자 케밥 값("sushi", "sns-hot")으로 받아
 * restaurant가 해석한다(지원하지 않는 값이면 RESTAURANT-001/005).
 * 컬렉션(imageKeys·menus·curationTypes·businessHours)은 전체 교체 의미다 — null이면 유지, 빈 리스트면
 * 비운다. 단 businessHours는 제공 시 7개 요일을 중복 없이 모두 포함해야 한다(위반 시 RESTAURANT-006).
 * 이미지 키는 업로드 완료된 S3 object key다.
 */
public record AdminRestaurantCommand(
        String name,
        String localName,
        String summary,
        String description,
        String address,
        String area,
        String genre,
        String foodCategory,
        String priceCurrency,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        List<String> imageKeys,
        List<MenuCommand> menus,
        List<String> hashtags,
        List<String> curationTypes,
        List<BusinessHourCommand> businessHours) {

    /** 메뉴 항목 — 목록 전체 교체 단위라 각 항목은 완전한 값으로 받는다. */
    public record MenuCommand(
            String name,
            String description,
            String imageKey,
            String priceCurrency,
            BigDecimal priceAmount,
            boolean main) {
    }

    /** 요일별 영업시간 — 휴무일(closed=true)은 시간 없이, 영업일은 open·close 필수(시간 규칙은 restaurant가 검증). */
    public record BusinessHourCommand(
            DayOfWeek dayOfWeek,
            LocalTime openTime,
            LocalTime closeTime,
            LocalTime breakStart,
            LocalTime breakEnd,
            boolean closed) {
    }
}
