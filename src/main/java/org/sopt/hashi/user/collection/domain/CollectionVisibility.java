package org.sopt.hashi.user.collection.domain;

import java.util.Arrays;
import java.util.Optional;

/** 컬렉션 공개 범위(#216). 공개 컬렉션은 누구나 조회할 수 있고 비공개는 소유자만 조회한다. */
public enum CollectionVisibility {

    PUBLIC("public"),
    PRIVATE("private");

    private final String value;

    CollectionVisibility(String value) {
        this.value = value;
    }

    public static Optional<CollectionVisibility> from(String value) {
        return Arrays.stream(values())
                .filter(visibility -> visibility.value.equals(value))
                .findFirst();
    }

    public String value() {
        return value;
    }
}
