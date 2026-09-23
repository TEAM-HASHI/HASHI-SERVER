package org.sopt.hashi.restaurant;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * 어드민 식당 등록·수정 커맨드 — 진입점(admin)이 {@link RestaurantPort}로 넘기는 계약.
 * 등록 시 필수 값 검증은 admin 요청 DTO(Bean Validation)가 담당하고, 수정(PATCH) 시 null 필드는
 * 변경하지 않는다. genre·curationTypes는 사용자 API와 같은 소문자 케밥 값("sushi", "sns-hot")으로 받아
 * restaurant가 해석한다(지원하지 않는 값이면 RESTAURANT-001/005). placeType(음식점 분류, #211)은
 * "restaurant"·"cafe"·"bar"로 받으며(지원하지 않는 값이면 RESTAURANT-010), 등록에서 null이면 음식점으로 둔다.
 * 컬렉션은 전체 교체 의미다. 수정에서 null이면 유지하며, imageKeys·hashtags는 최소 1개를 유지해야 한다.
 * businessHours는 제공 시 7개 요일을 중복 없이 모두 포함해야 한다(위반 시 RESTAURANT-006).
 * 이미지 키는 업로드 완료된 S3 object key다. 신규 media 식당 이미지는 등록에서
 * {@code imageAssetIds}, 수정에서 stable association을 포함한 {@code images}만 사용하며,
 * 반대 동작의 필드가 전달되면 묵시적으로 무시하지 않고 잘못된 입력으로 거부한다.
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
        String placeType,
        String priceCurrency,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        List<String> imageKeys,
        List<UUID> imageAssetIds,
        List<ImageCommand> images,
        List<MenuCommand> menus,
        List<String> hashtags,
        List<String> curationTypes,
        List<BusinessHourCommand> businessHours) {

    /** 수정 collection의 유지 association 또는 신규 asset. 배열 위치가 최종 순서다. */
    public record ImageCommand(Long restaurantImageId, UUID imageAssetId) {
    }

    /** 메뉴 항목 — 수정 시 기존 메뉴는 menuId를, 신규 메뉴는 null을 전달한다. */
    public record MenuCommand(
            Long menuId,
            String name,
            String description,
            String imageKey,
            UUID imageAssetId,
            String priceCurrency,
            BigDecimal priceAmount,
            boolean main) {

        /** 식당 등록·개발 데이터처럼 모든 메뉴가 신규인 호출을 위한 생성자. */
        public MenuCommand(
                String name,
                String description,
                String imageKey,
                String priceCurrency,
                BigDecimal priceAmount,
                boolean main
        ) {
            this(null, name, description, imageKey, null, priceCurrency, priceAmount, main);
        }

        /** legacy 수정 호출부를 위한 생성자. */
        public MenuCommand(
                Long menuId,
                String name,
                String description,
                String imageKey,
                String priceCurrency,
                BigDecimal priceAmount,
                boolean main
        ) {
            this(menuId, name, description, imageKey, null, priceCurrency, priceAmount, main);
        }
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
