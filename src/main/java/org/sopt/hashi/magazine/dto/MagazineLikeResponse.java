package org.sopt.hashi.magazine.dto;

/** 좋아요 등록·취소 결과 — 클라이언트가 낙관적으로 갱신한 하트 상태·수를 서버 값으로 맞추는 데 쓴다. */
public record MagazineLikeResponse(
        Long magazineId,
        boolean liked,
        long likeCount) {
}
