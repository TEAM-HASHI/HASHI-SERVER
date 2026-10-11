package org.sopt.hashi.restaurant.domain;

/** 운영자 확인만으로 Google 유래 좌표의 출처를 ADMIN으로 바꾸지 않는다. */
public enum RestaurantLocationSource {
    GOOGLE_GEOCODING,
    GOOGLE_PLACES,
    ADMIN
}
