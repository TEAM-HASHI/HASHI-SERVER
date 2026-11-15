package org.sopt.hashi.support.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.support.NoticeBlock;
import org.sopt.hashi.support.NoticeCommand;

class NoticeContentCodecTest {
    private final NoticeContentCodec codec = new NoticeContentCodec(new ObjectMapper());

    private NoticeCommand command(String text, String href, List<UUID> images) {
        return new NoticeCommand("공지", List.of(new NoticeBlock(NoticeBlock.Type.PARAGRAPH,
                List.of(List.of(new NoticeBlock.Span(text, true, href))))), images);
    }

    @Test
    void 허용한_서식과_일반텍스트를_왕복한다() {
        NoticeCommand command = command("<script>는 텍스트\n줄바꿈", "/notices/1", List.of());
        assertThat(codec.decode(codec.validateAndEncode(command))).isEqualTo(command.body());
    }

    @ParameterizedTest
    @ValueSource(strings = {"javascript:alert(1)", "data:text/html,x", "//evil.example", "/\\evil",
            " https://example.com", "https://user:pass@example.com", "", "%2f%2fevil.example"})
    void 위험한_링크는_거절한다(String href) {
        assertThatThrownBy(() -> codec.validateAndEncode(command("본문", href, List.of())))
                .isInstanceOf(BusinessException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/notices/1", "https://example.com/path?q=1", "http://example.com"})
    void 내부경로와_HTTP링크는_허용한다(String href) {
        assertThat(codec.validateAndEncode(command("본문", href, List.of()))).isNotBlank();
    }

    @Test
    void 본문은_10000자까지_이미지는_10개까지_허용한다() {
        List<UUID> images = java.util.stream.IntStream.range(0, 10).mapToObj(i -> UUID.randomUUID()).toList();
        assertThat(codec.validateAndEncode(command("a".repeat(10000), null, images))).isNotBlank();
        assertThatThrownBy(() -> codec.validateAndEncode(command("a".repeat(10001), null, images)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> codec.validateAndEncode(command("본문", null,
                java.util.stream.IntStream.range(0, 11).mapToObj(i -> UUID.randomUUID()).toList())))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 링크주소는_본문_글자수와_분리하되_2000자_한도를_지킨다() {
        String href = "/" + "a".repeat(1999);
        assertThat(codec.validateAndEncode(command("가".repeat(10000), href, List.of()))).isNotBlank();
        assertThatThrownBy(() -> codec.validateAndEncode(command("본문", href + "a", List.of())))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 표시글자수가_유효해도_저장할_JSON이_MEDIUMTEXT를_넘으면_입력오류다() {
        var span = new NoticeBlock.Span("가", false, "/" + "a".repeat(1999));
        var block = new NoticeBlock(NoticeBlock.Type.PARAGRAPH,
                List.of(java.util.Collections.nCopies(1000, span)));
        var command = new NoticeCommand("공지", java.util.Collections.nCopies(10, block), List.of());
        assertThatThrownBy(() -> codec.validateAndEncode(command)).isInstanceOf(BusinessException.class);
    }

    @Test
    void 공백본문과_중복이미지를_거절한다() {
        assertThatThrownBy(() -> codec.validateAndEncode(command(" ", null, List.of())))
                .isInstanceOf(BusinessException.class);
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> codec.validateAndEncode(command("본문", null, List.of(id, id))))
                .isInstanceOf(BusinessException.class);
    }
}
