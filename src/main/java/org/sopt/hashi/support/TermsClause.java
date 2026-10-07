package org.sopt.hashi.support;

/** 일반 텍스트 조항. 배열 순서대로 accordion을 표시한다. HTML은 렌더링하지 않는다. */
public record TermsClause(String heading, String content) {}
