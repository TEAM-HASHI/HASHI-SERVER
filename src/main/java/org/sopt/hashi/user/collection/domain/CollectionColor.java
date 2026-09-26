package org.sopt.hashi.user.collection.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * 컬렉션 커버 색상(#216). 기획 시안의 6색을 코드값으로 주고받고, 실제 색상(hex)은 클라이언트가 매핑한다.
 * 사용자 API와 같은 소문자 값("red", "orange", …)으로 받는다.
 */
public enum CollectionColor {

    RED("red"),
    ORANGE("orange"),
    YELLOW("yellow"),
    GREEN("green"),
    BLUE("blue"),
    PURPLE("purple");

    private final String value;

    CollectionColor(String value) {
        this.value = value;
    }

    public static Optional<CollectionColor> from(String value) {
        return Arrays.stream(values())
                .filter(color -> color.value.equals(value))
                .findFirst();
    }

    public String value() {
        return value;
    }
}
