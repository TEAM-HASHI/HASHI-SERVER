package org.sopt.hashi.magazine.code;

import lombok.Getter;
import org.sopt.hashi.shared.error.ErrorCode;
import org.springframework.http.HttpStatus;

@Getter
public enum MagazineErrorCode implements ErrorCode {

    NOT_FOUND(HttpStatus.NOT_FOUND, "MAGAZINE-001", "매거진을 찾을 수 없습니다."),
    /** 연결 식당이 없거나 삭제된 식당일 때 — 삭제된 식당은 사용자에게 노출되지 않으므로 없는 식당과 같게 본다. */
    RESTAURANT_NOT_FOUND(HttpStatus.NOT_FOUND, "MAGAZINE-002", "연결하려는 식당을 찾을 수 없습니다."),
    CARD_NEWS_ASSET_DUPLICATED(HttpStatus.BAD_REQUEST, "MAGAZINE-003",
            "같은 카드뉴스 이미지를 중복해서 사용할 수 없습니다."),
    /** 해시태그 유니크 제약에 걸렸을 때 — DB가 대소문자 등을 같은 값으로 봐 완전히 같지 않은 문자열도 해당된다. */
    HASHTAG_DUPLICATED(HttpStatus.BAD_REQUEST, "MAGAZINE-004", "같은 해시태그를 중복해서 사용할 수 없습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    MagazineErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }
}
