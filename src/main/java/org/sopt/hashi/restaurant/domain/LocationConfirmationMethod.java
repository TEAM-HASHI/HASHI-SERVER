package org.sopt.hashi.restaurant.domain;

/** 좌표 제공자(source)와 별개로 누가 어떤 방식으로 위치를 확인했는지 기록한다. */
public enum LocationConfirmationMethod {
    AUTOMATIC,
    ADMIN_PLACE_SELECTION
}
