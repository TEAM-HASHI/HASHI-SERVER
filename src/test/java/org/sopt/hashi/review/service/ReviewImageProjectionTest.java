package org.sopt.hashi.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.media.MediaImage;
import org.sopt.hashi.media.MediaImageRequest;
import org.sopt.hashi.media.MediaImageRole;
import org.sopt.hashi.media.MediaImageStatus;
import org.sopt.hashi.review.domain.ReviewImage;
import org.sopt.hashi.shared.storage.FileStorage;
import org.springframework.test.util.ReflectionTestUtils;

class ReviewImageProjectionTest {
    @Test
    void 모든_슬롯은_유지하고_READY와_legacy_URL만_호환_배열에_반환한다() {
        var legacy = ReviewImage.create("legacy.jpg", 0);
        var ready = ReviewImage.createAsset(UUID.randomUUID(), 1);
        var processing = ReviewImage.createAsset(UUID.randomUUID(), 2);
        var failed = ReviewImage.createAsset(UUID.randomUUID(), 3);
        var missing = ReviewImage.createAsset(UUID.randomUUID(), 4);
        for (var image : List.of(ready, processing, failed, missing)) {
            ReflectionTestUtils.setField(image, "fileKey", "private-original-must-not-leak");
        }
        FileStorage storage = mock(FileStorage.class);
        when(storage.resolveFileUrl("legacy.jpg")).thenReturn("https://cdn.test/legacy.jpg");
        var role = MediaImageRole.REVIEW_PREVIEW;
        Map<MediaImageRequest, MediaImage> loaded = Map.of(
                new MediaImageRequest(ready.getImageAssetId(), role), new MediaImage(ready.getImageAssetId(), role,
                        MediaImageStatus.READY, new MediaImage.Source("https://cdn.test/ready.webp", 270, 270, "image/webp"),
                        List.of(new MediaImage.SourceSet("image/webp",
                                List.of(new MediaImage.Candidate("https://cdn.test/ready.webp", 270, 270))))),
                new MediaImageRequest(processing.getImageAssetId(), role), new MediaImage(processing.getImageAssetId(), role,
                        MediaImageStatus.PROCESSING, null, List.of()),
                new MediaImageRequest(failed.getImageAssetId(), role), new MediaImage(failed.getImageAssetId(), role,
                        MediaImageStatus.FAILED, null, List.of()));
        var projected = ReviewImageProjection.project(List.of(legacy, ready, processing, failed, missing),
                role, loaded, storage);
        assertThat(projected).hasSize(5);
        assertThat(projected).extracting(image -> image.displayOrder()).containsExactly(0, 1, 2, 3, 4);
        assertThat(ReviewImageProjection.urls(projected))
                .containsExactly("https://cdn.test/legacy.jpg", "https://cdn.test/ready.webp");
        assertThat(projected.subList(1, 5)).allSatisfy(image -> assertThat(image.legacyUrl()).isNull());
        verify(storage).resolveFileUrl("legacy.jpg");
        verifyNoMoreInteractions(storage);
    }
}
