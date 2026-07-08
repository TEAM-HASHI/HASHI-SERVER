package org.sopt.hashi.point;

/**
 * 포인트 변동을 일으킨 출처 도메인. 원장(point_transaction)의 source_type으로 기록되어
 * "어느 도메인의 어떤 건(source_id) 때문에 변동했는지"를 추적하고, 복원 멱등 판정의 기준이 된다.
 * 포트 파라미터로 쓰이므로 모듈 루트에 공개한다(값은 식별자일 뿐 타 모듈 의존이 아니다).
 */
public enum PointSourceType {

    RESERVATION,
    REVIEW
}
