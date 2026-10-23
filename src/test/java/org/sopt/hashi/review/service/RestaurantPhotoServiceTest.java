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
    void 리뷰_asset은_DETAIL로_bulk조회하고_누락된_슬롯도_원본없이_유지한다() {
        UUID readyId = UUID.randomUUID();
        UUID missingId = UUID.randomUUID();
        when(restaurants.existsActiveById(1L)).thenReturn(true);
        when(reviews.count(1L, false, List.of())).thenReturn(2L);
        when(reviews.count(1L, true, List.of())).thenReturn(2L);
        when(reviews.find(1L, false, List.of(), null, 21)).thenReturn(List.of(
                new ReviewPhotoRepository.Photo(10L, null, readyId,
                        new ReviewPhotoRepository.Position(null, 5L, 0, 10L)),
                new ReviewPhotoRepository.Photo(11L, "private-key", missingId,
                        new ReviewPhotoRepository.Position(null, 5L, 1, 11L))));
        var request = new MediaImageRequest(readyId, MediaImageRole.REVIEW_DETAIL);
        when(media.findImages(anyCollection())).thenReturn(Map.of(request, ready(readyId, request.role())));
        var response = service.getPhotos(1L, RestaurantPhotoFilter.REVIEW, null);
        assertThat(response.photos()).hasSize(2);
        assertThat(response.totalCount()).isEqualTo(2);
        assertThat(response.counts().review()).isEqualTo(2);
        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();
        assertThat(response.photos().getFirst().image().role()).isEqualTo(MediaImageRole.REVIEW_DETAIL);
        assertThat(response.photos().get(1).imageUrl()).isNull();
        assertThat(response.photos().get(1).image()).isNull();
        org.mockito.Mockito.verify(media).findImages(List.of(request,
                new MediaImageRequest(missingId, MediaImageRole.REVIEW_DETAIL)));
        verifyNoInteractions(storage);
    }

    @Test
    void 혼합_진행은_대표1_리뷰3이며_한_출처가_끝나면_나머지_후_메뉴이다() {
        assertThat(RestaurantPhotoService.nextSource(0, false, true, true)).isEqualTo(RestaurantPhotoFilter.REPRESENTATIVE);
        for (int phase = 1; phase <= 3; phase++) {
            assertThat(RestaurantPhotoService.nextSource(phase, false, true, true)).isEqualTo(RestaurantPhotoFilter.REVIEW);
        }
        assertThat(RestaurantPhotoService.nextSource(1, false, true, false)).isEqualTo(RestaurantPhotoFilter.REPRESENTATIVE);
        assertThat(RestaurantPhotoService.nextSource(0, false, false, true)).isEqualTo(RestaurantPhotoFilter.REVIEW);
        assertThat(RestaurantPhotoService.nextSource(0, false, false, false)).isEqualTo(RestaurantPhotoFilter.MENU);
        assertThat(RestaurantPhotoService.nextSource(0, true, true, true)).isEqualTo(RestaurantPhotoFilter.MENU);
    }

    @Test
    void 미존재_삭제_식당은_사진을_조회하지_않는다() {
        assertThatThrownBy(() -> service.getPhotos(1, RestaurantPhotoFilter.ALL, null)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(reviews, media, storage);
    }

    @Test
    void 잘못된_커서와_잘못된_식당은_거절한다() {
        assertThatThrownBy(() -> service.getPhotos(1, RestaurantPhotoFilter.ALL, "invalid")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.getPhotos(0, RestaurantPhotoFilter.ALL, null)).isInstanceOf(BusinessException.class);
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
            var result = service.getPhotos(1, RestaurantPhotoFilter.ALL, null);
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
        var result = service.getPhotos(1, RestaurantPhotoFilter.ALL, null);
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
