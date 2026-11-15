package org.sopt.hashi.restaurant.internal.map;

import lombok.extern.slf4j.Slf4j;

/** DB 오류의 SQL, 입력값, 연결 정보와 stack trace를 기록하지 않는 지도 조회 진단. */
@Slf4j
public final class MapQueryFailureLogger {

    private MapQueryFailureLogger() {
    }

    public static void warn(RuntimeException exception) {
        log.warn("Map query failed. operation=restaurant-map-query exceptionType={}",
                exception.getClass().getSimpleName());
    }
}
