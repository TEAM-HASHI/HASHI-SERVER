package org.sopt.hashi.restaurant.internal.map;

/**
 * restaurant 내부의 주소 조회 경계. 후보는 채택/저장된 위치가 아니며 호출자는 DB transaction 밖에서 호출한다.
 * 재시도, 국가/주소 일치 판정, 저장 정밀도와 보관 수명은 후속 작업 처리 서비스가 담당한다.
 */
public interface GeocodingProvider {

    GeocodingResult geocode(String address);
}
