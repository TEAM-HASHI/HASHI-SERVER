package org.sopt.hashi.user.collection.dto;

/** 저장 식당 이동 결과 — 원래·대상 컬렉션의 갱신된 저장 수를 함께 내려 클라이언트가 두 목록을 갱신한다. */
public record MoveSavedRestaurantsResponse(
        RestaurantCollectionResponse sourceCollection,
        RestaurantCollectionResponse targetCollection) {
}
