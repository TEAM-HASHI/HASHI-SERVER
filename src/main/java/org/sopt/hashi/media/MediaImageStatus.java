package org.sopt.hashi.media;

/** 콘텐츠 응답에 노출하는 이미지 처리 상태. */
public enum MediaImageStatus {

    PROCESSING,
    READY,
    FAILED,
    /** 요청한 화면용 파생 이미지가 없는 상태. 자동 처리 중이라는 의미가 아니다. */
    UNAVAILABLE
}
