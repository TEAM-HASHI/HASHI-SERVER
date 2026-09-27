package org.sopt.hashi.magazine.dto;

import org.sopt.hashi.magazine.code.MagazineSuccessCode;

/**
 * 좋아요 등록·취소 처리 결과 — 응답 본문과, 실제로 상태가 바뀌었는지에 따라 달라지는 성공 코드를 함께 담는다.
 * 컨트롤러는 분기 없이 이 코드를 그대로 응답 봉투에 싣는다.
 */
public record MagazineLikeResult(
        MagazineSuccessCode code,
        MagazineLikeResponse response) {
}
