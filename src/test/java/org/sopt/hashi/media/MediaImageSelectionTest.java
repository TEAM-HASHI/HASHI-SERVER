package org.sopt.hashi.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class MediaImageSelectionTest {

    @Test
    void 참조가_없으면_이미지를_노출하지_않는다() {
        assertThat(MediaImageSelection.from(null, readyImage(UUID.randomUUID())))
                .isEqualTo(new MediaImageSelection(null, null, null));
    }

    @Test
    void asset이_없는_기존_사진만_기존_URL을_사용한다() {
        assertThat(MediaImageSelection.from(ImageReference.legacy("https://cdn/old.jpg"),
                readyImage(UUID.randomUUID())))
                .isEqualTo(new MediaImageSelection("https://cdn/old.jpg", null, "https://cdn/old.jpg"));
    }

    @Test
    void READY는_새_URL을_사용하고_기존_URL을_노출하지_않는다() {
        UUID id = UUID.randomUUID();
        MediaImage image = readyImage(id);
        assertThat(MediaImageSelection.from(new ImageReference(id, "https://cdn/old.jpg"), image))
                .isEqualTo(new MediaImageSelection("https://cdn/new.webp", image, null));
    }

    @ParameterizedTest
    @EnumSource(value = MediaImageStatus.class, names = {"PROCESSING", "FAILED"})
    void 처리중이나_실패한_asset은_기존_URL로_우회하지_않는다(MediaImageStatus status) {
        UUID id = UUID.randomUUID();
        MediaImage image = new MediaImage(id, MediaImageRole.RESTAURANT_CARD, status, null, List.of());
        assertThat(MediaImageSelection.from(new ImageReference(id, "https://cdn/old.jpg"), image))
                .isEqualTo(new MediaImageSelection(null, image, null));
    }

    @Test
    void 조회_결과가_없어도_기존_URL로_우회하지_않는다() {
        assertThat(MediaImageSelection.from(new ImageReference(UUID.randomUUID(), "https://cdn/old.jpg"), null))
                .isEqualTo(new MediaImageSelection(null, null, null));
    }

    @Test
    void 다른_asset의_이미지를_노출하지_않는다() {
        assertThat(MediaImageSelection.from(ImageReference.asset(UUID.randomUUID()), readyImage(UUID.randomUUID())))
                .isEqualTo(new MediaImageSelection(null, null, null));
    }

    private MediaImage readyImage(UUID id) {
        return new MediaImage(id, MediaImageRole.RESTAURANT_CARD, MediaImageStatus.READY,
                new MediaImage.Source("https://cdn/new.webp", 270, 270, "image/webp"),
                List.of(new MediaImage.SourceSet("image/webp", List.of(
                        new MediaImage.Candidate("https://cdn/new.webp", 270, 270)))));
    }
}
