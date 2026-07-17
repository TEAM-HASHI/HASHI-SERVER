package org.sopt.hashi.user;

import org.springframework.data.domain.Sort;

/**
 * 어드민 회원 목록 정렬 기준. 닉네임은 유니크라 단독으로 순서가 결정되지만,
 * 가입 시각은 동시 가입으로 겹칠 수 있어 id를 보조 정렬로 둔다.
 */
public enum AdminUserSortType {

    /** 닉네임 가나다순(기본). */
    NICKNAME(Sort.by(Sort.Direction.ASC, "nickname")),

    /** 가입일 최신순. */
    CREATED_AT(Sort.by(Sort.Direction.DESC, "createdAt")
            .and(Sort.by(Sort.Direction.DESC, "id")));

    private final Sort sort;

    AdminUserSortType(Sort sort) {
        this.sort = sort;
    }

    public Sort toSort() {
        return sort;
    }
}
