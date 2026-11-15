package org.sopt.hashi.magazine.domain;

/** 매거진 리액션 상태 — 취소해도 행을 남기고 INACTIVE로 내린다. 집계·표시는 ACTIVE만 센다. */
public enum MagazineReactionStatus {

    ACTIVE,
    INACTIVE
}
