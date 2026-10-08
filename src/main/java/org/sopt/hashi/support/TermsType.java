package org.sopt.hashi.support;

/** 선언 순서가 TERMS 화면 고정 순서다. */
public enum TermsType {
    SERVICE_TERMS("Hashi 이용약관"),
    PRIVACY_POLICY("개인정보처리방침"),
    PERSONAL_DATA_COLLECTION("개인정보 수집 및 이용 동의"),
    PERSONAL_DATA_THIRD_PARTY("개인정보 제3자 제공 동의"),
    RESERVATION_REFUND_POLICY("예약 및 취소·환불 정책"),
    REVIEW_POLICY("리뷰 운영정책"),
    POINT_TERMS("포인트 이용약관"),
    SERVICE_POLICY("서비스 운영정책");

    private final String title;
    TermsType(String title) { this.title = title; }
    public String title() { return title; }
}
