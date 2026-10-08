package org.sopt.hashi.support;

import java.util.List;

/** 본문 구조. text는 HTML이 아닌 일반 텍스트이며 줄바꿈을 그대로 표시한다. */
public record NoticeBlock(Type type, List<List<Span>> items) {
    public enum Type { PARAGRAPH, BULLET_LIST, ORDERED_LIST }
    public record Span(String text, boolean bold, String href) {}
}
