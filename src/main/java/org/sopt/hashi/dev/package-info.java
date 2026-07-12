/**
 * 개발용 더미데이터 진입점 모듈 — admin처럼 자체 도메인 없이 각 모듈의 dev 생성기(named interface)로
 * 위임해 테스트 시나리오를 조립한다. 모든 빈이 local·dev 프로필 전용이라 운영에는 존재하지 않는다.
 */
@org.springframework.modulith.ApplicationModule
package org.sopt.hashi.dev;
