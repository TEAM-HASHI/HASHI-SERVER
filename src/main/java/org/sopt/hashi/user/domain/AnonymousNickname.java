package org.sopt.hashi.user.domain;

import java.util.List;

/**
 * 탈퇴 회원의 리뷰 작성자 표시용 익명 닉네임 후보(REVIEW_POLICY §2.1 표, 예약어 규칙은 §3). 탈퇴 시 회원 id로 후보 하나를 정해
 * anonymous_nickname에 저장하므로 같은 회원의 모든 리뷰에 같은 이름이 보인다. 후보는 유니크가 아니라
 * 여러 탈퇴 회원이 같은 이름을 쓸 수 있고, 활성 회원이 후보와 같은 닉네임을 쓰면 탈퇴 회원과 구분되지 않아 온보딩에서 거절한다.
 */
public final class AnonymousNickname {

    public static final List<String> CANDIDATES = List.of(
            "한입여행자", "미식산책", "오늘의한끼", "맛집탐험가", "식탁기록자",
            "한끼수집가", "동네미식가", "먹는일상", "식사메모", "맛있는발걸음",
            "한입기록", "식탁여행", "오늘의미식", "맛집산책", "한끼발견",
            "먹거리탐방", "식사여행자", "맛있는기억", "미식노트", "동네한끼");

    private AnonymousNickname() {
    }

    /** 회원 id로 후보를 정한다 — 같은 회원은 항상 같은 이름을 받는다. */
    public static String assignFor(Long userId) {
        return CANDIDATES.get(Math.floorMod(userId, CANDIDATES.size()));
    }

    public static boolean isCandidate(String nickname) {
        return CANDIDATES.contains(nickname);
    }
}
