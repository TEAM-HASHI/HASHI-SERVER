package org.sopt.hashi.magazine.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MagazineContentTest {

    @Test
    void 등록은_본문을_함께_받고_공백뿐인_본문은_없는_것으로_저장한다() {
        assertThat(magazine("퇴근길에 들르기 좋은 곳").getContent()).isEqualTo("퇴근길에 들르기 좋은 곳");
        assertThat(magazine(null).getContent()).isNull();
        assertThat(magazine("   ").getContent()).isNull();
    }

    @Test
    void 수정에서_본문을_보내지_않으면_기존_본문을_유지한다() {
        Magazine magazine = magazine("원래 본문");

        update(magazine, "제목만 변경", null);

        assertThat(magazine.getTitle()).isEqualTo("제목만 변경");
        assertThat(magazine.getContent()).isEqualTo("원래 본문");
    }

    @Test
    void 수정에서_본문을_보내면_바꾸고_빈_문자열이면_지운다() {
        Magazine magazine = magazine("원래 본문");

        update(magazine, null, "바뀐 본문");
        assertThat(magazine.getContent()).isEqualTo("바뀐 본문");

        update(magazine, null, "");
        assertThat(magazine.getContent()).isNull();
    }

    private Magazine magazine(String content) {
        return Magazine.create(
                "매거진", "magazines/banner.jpg", null, "magazines/thumbnail.jpg", null,
                "https://www.instagram.com/p/test/", content);
    }

    private void update(Magazine magazine, String title, String content) {
        magazine.update(
                title, magazine.getBannerKey(), null, magazine.getThumbnailKey(), null,
                null, content);
    }
}
