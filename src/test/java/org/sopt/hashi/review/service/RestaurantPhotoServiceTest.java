package org.sopt.hashi.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.sopt.hashi.media.MediaImage;
import org.sopt.hashi.media.MediaImageRequest;
import org.sopt.hashi.media.MediaImageRole;
import org.sopt.hashi.media.MediaImageStatus;
import org.sopt.hashi.media.MediaPort;
import org.sopt.hashi.restaurant.RestaurantPhotoSourceInfo;
import org.sopt.hashi.restaurant.RestaurantPort;
import org.sopt.hashi.review.domain.RestaurantPhotoFilter;
import org.sopt.hashi.review.domain.ReviewPhotoRepository;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.storage.FileStorage;

class RestaurantPhotoServiceTest {
    private final RestaurantPort restaurants = mock(RestaurantPort.class);
    private final ReviewPhotoRepository reviews = mock(ReviewPhotoRepository.class);
    private final MediaPort media = mock(MediaPort.class);
    private final FileStorage storage = mock(FileStorage.class);
    private final RestaurantPhotoService service = new RestaurantPhotoService(restaurants, reviews, media, storage);

    @Test
    void 조합_위치는_출처별_사진수와_페이지_경계에_관계없이_기획_순서와_일치한다() {
        for (int representative = 0; representative <= 25; representative++) {
            for (int review = 0; review <= 65; review++) {
                List<RestaurantPhotoService.Slot> expected = new ArrayList<>();
                int r = 0;
                int v = 0;
                while (r < representative || v < review) {
                    if (r < representative) expected.add(new RestaurantPhotoService.Slot(RestaurantPhotoFilter.REPRESENTATIVE, r++));
                    for (int i = 0; i < 3 && v < review; i++) {
                        expected.add(new RestaurantPhotoService.Slot(RestaurantPhotoFilter.REVIEW, v++));
                    }
                }
                for (int i = 0; i < 8; i++) expected.add(new RestaurantPhotoService.Slot(RestaurantPhotoFilter.MENU, i));
                for (int i = 0; i < expected.size(); i++) {
                    assertThat(RestaurantPhotoService.slot(i, representative, review)).isEqualTo(expected.get(i));
                }
            }
        }
    }

    @Test
    void 미존재_삭제_식당은_사진을_조회하지_않는다() {
        assertThatThrownBy(() -> service.getPhotos(1, RestaurantPhotoFilter.ALL, 0)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(reviews, media, storage);
    }

    @Test
    void 음수_페이지와_잘못된_식당은_거절한다() {
        assertThatThrownBy(() -> service.getPhotos(1, RestaurantPhotoFilter.ALL, -1)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.getPhotos(0, RestaurantPhotoFilter.ALL, 0)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(restaurants, reviews, media, storage);
    }

    @Test
    void 비공개_asset은_제외하고_미준비_asset은_legacy_URL로_우회하지_않는다() {
        UUID processing = UUID.randomUUID();
        UUID unavailable = UUID.randomUUID();
        when(restaurants.existsActiveById(1L)).thenReturn(true);
        when(restaurants.findRepresentativePhotoSources(1L)).thenReturn(List.of(
                new RestaurantPhotoSourceInfo(1L, "private-original", processing),
                new RestaurantPhotoSourceInfo(2L, "retired-original", unavailable)));
        when(restaurants.findMenuPhotoSources(1L)).thenReturn(List.of());
        MediaImageRequest request = new MediaImageRequest(processing, MediaImageRole.PHOTO_DETAIL);
        for (MediaImageStatus status : List.of(MediaImageStatus.PROCESSING, MediaImageStatus.FAILED, MediaImageStatus.UNAVAILABLE)) {
            when(media.findImages(anyCollection())).thenReturn(Map.of(request,
                    new MediaImage(processing, request.role(), status, null, List.of())));
            var result = service.getPhotos(1, RestaurantPhotoFilter.ALL, 0);
            assertThat(result.totalCount()).isEqualTo(1);
            assertThat(result.photos()).hasSize(1);
            assertThat(result.photos().getFirst().imageUrl()).isNull();
            assertThat(result.photos().getFirst().image().status()).isEqualTo(status);
        }
        verifyNoInteractions(storage);
    }

    @Test
    void backfill_참조와_legacy_참조는_동일_원본으로_중복_제거한다() {
        UUID asset = UUID.randomUUID();
        when(restaurants.existsActiveById(1L)).thenReturn(true);
        when(restaurants.findRepresentativePhotoSources(1L)).thenReturn(List.of(
                new RestaurantPhotoSourceInfo(1L, "same-key", asset),
                new RestaurantPhotoSourceInfo(2L, "same-key", null)));
        when(restaurants.findMenuPhotoSources(1L)).thenReturn(List.of(new RestaurantPhotoSourceInfo(3L, null, asset)));
        when(media.findImages(anyCollection())).thenReturn(Map.of(
                new MediaImageRequest(asset, MediaImageRole.PHOTO_DETAIL), ready(asset, MediaImageRole.PHOTO_DETAIL)));
        var result = service.getPhotos(1, RestaurantPhotoFilter.ALL, 0);
        assertThat(result.counts().representative()).isEqualTo(2);
        assertThat(result.counts().menu()).isEqualTo(1);
        assertThat(result.counts().all()).isEqualTo(1);
        assertThat(result.photos().getFirst().imageUrl()).isEqualTo("https://images.test/ready.webp");
        verifyNoInteractions(storage);
    }

    private static MediaImage ready(UUID id, MediaImageRole role) {
        return new MediaImage(id, role, MediaImageStatus.READY,
                new MediaImage.Source("https://images.test/ready.webp", 800, 600, "image/webp"),
                List.of(new MediaImage.SourceSet("image/webp", List.of(new MediaImage.Candidate("https://images.test/ready.webp", 800, 600)))));
    }
}
