package org.sopt.hashi.magazine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sopt.hashi.auth.CurrentUserProvider;
import org.sopt.hashi.magazine.AdminMagazineCommand;
import org.sopt.hashi.magazine.AdminMagazineCommand.ImageCommand;
import org.sopt.hashi.magazine.domain.Magazine;
import org.sopt.hashi.magazine.domain.MagazineMetaRepository;
import org.sopt.hashi.magazine.domain.MagazineReactionRepository;
import org.sopt.hashi.magazine.domain.MagazineRepository;
import org.sopt.hashi.media.MediaAssetPurpose;
import org.sopt.hashi.media.MediaAssetUse;
import org.sopt.hashi.media.MediaImage;
import org.sopt.hashi.media.MediaImage.Candidate;
import org.sopt.hashi.media.MediaImage.Source;
import org.sopt.hashi.media.MediaImage.SourceSet;
import org.sopt.hashi.media.MediaImageRequest;
import org.sopt.hashi.media.MediaImageRole;
import org.sopt.hashi.media.MediaImageStatus;
import org.sopt.hashi.media.MediaPort;
import org.sopt.hashi.media.code.MediaErrorCode;
import org.sopt.hashi.restaurant.RestaurantPort;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.storage.FileStorage;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class MagazineServiceTest {

    @Mock
    private MagazineRepository magazineRepository;

    @Mock
    private MagazineMetaRepository magazineMetaRepository;

    @Mock
    private MagazineReactionRepository magazineReactionRepository;

    @Mock
    private RestaurantPort restaurantPort;

    @Mock
    private CurrentUserProvider currentUserProvider;

    @Mock
    private FileStorage fileStorage;

    @Mock
    private MediaPort mediaPort;

    private MagazineService magazineService;

    @BeforeEach
    void setUp() {
        magazineService = new MagazineService(
                magazineRepository, magazineMetaRepository, magazineReactionRepository,
                restaurantPort, currentUserProvider, fileStorage, mediaPort);
        lenient().when(fileStorage.resolveFileUrl(anyString()))
                .thenAnswer(invocation -> "https://cdn.hashi.test/" + invocation.getArgument(0));
        lenient().when(magazineRepository.save(any(Magazine.class))).thenAnswer(invocation -> {
            Magazine magazine = invocation.getArgument(0);
            ReflectionTestUtils.setField(magazine, "id", 1L);
            ReflectionTestUtils.setField(magazine, "createdAt", LocalDateTime.of(2026, 8, 31, 1, 0));
            return magazine;
        });
    }

    @Test
    void asset_등록은_두_슬롯의_purpose를_구분해_claim하고_최적화_응답을_반환한다() {
        UUID bannerId = UUID.randomUUID();
        UUID thumbnailId = UUID.randomUUID();
        MediaImageRequest bannerRequest = request(bannerId, MediaImageRole.MAGAZINE_BANNER);
        MediaImageRequest thumbnailRequest = request(thumbnailId, MediaImageRole.MAGAZINE_THUMBNAIL);
        MediaImage bannerImage = readyImage(bannerId, MediaImageRole.MAGAZINE_BANNER);
        MediaImage thumbnailImage = readyImage(thumbnailId, MediaImageRole.MAGAZINE_THUMBNAIL);
        when(mediaPort.findImages(List.of(bannerRequest, thumbnailRequest)))
                .thenReturn(Map.of(bannerRequest, bannerImage, thumbnailRequest, thumbnailImage));

        var response = magazineService.create(new AdminMagazineCommand(
                "asset 매거진", new ImageCommand(null, bannerId),
                new ImageCommand(null, thumbnailId), "https://www.instagram.com/p/test/"));

        verify(mediaPort).reconcileBindings(
                List.of(
                        new MediaAssetUse(bannerId, MediaAssetPurpose.MAGAZINE_BANNER),
                        new MediaAssetUse(thumbnailId, MediaAssetPurpose.MAGAZINE_THUMBNAIL)),
                List.of());
        assertThat(response.bannerImageUrl()).isEqualTo(bannerImage.defaultSource().url());
        assertThat(response.bannerImage()).isEqualTo(bannerImage);
        assertThat(response.thumbnailImage()).isEqualTo(thumbnailImage);
    }

    @Test
    void legacy_등록은_기존_URL을_보존하고_media를_호출하지_않는다() {
        var response = magazineService.create(new AdminMagazineCommand(
                "legacy 매거진", new ImageCommand("magazines/banner.jpg", null),
                new ImageCommand("magazines/thumbnail.jpg", null),
                "https://www.instagram.com/p/test/"));

        assertThat(response.bannerImageUrl()).isEqualTo("https://cdn.hashi.test/magazines/banner.jpg");
        assertThat(response.bannerImage()).isNull();
        verify(mediaPort, never()).reconcileBindings(anyCollection(), anyCollection());
        verify(mediaPort, never()).findImages(anyCollection());
    }

    @Test
    void 같은_asset_재전달은_noop이고_backfill_key도_보존한다() {
        UUID bannerId = UUID.randomUUID();
        Magazine magazine = magazine("magazines/banner.jpg", bannerId, "magazines/thumb.jpg", null);
        when(magazineRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(magazine));

        magazineService.update(1L, new AdminMagazineCommand(
                "제목만 변경", new ImageCommand(null, bannerId), null, null));

        assertThat(magazine.getTitle()).isEqualTo("제목만 변경");
        assertThat(magazine.getBannerKey()).isEqualTo("magazines/banner.jpg");
        assertThat(magazine.getBannerImageAssetId()).isEqualTo(bannerId);
        verify(mediaPort, never()).reconcileBindings(anyCollection(), anyCollection());
    }

    @Test
    void 새_asset_교체는_새_claim과_기존_retire를_함께_계획한다() {
        UUID oldBannerId = UUID.randomUUID();
        UUID newBannerId = UUID.randomUUID();
        Magazine magazine = magazine(null, oldBannerId, "magazines/thumb.jpg", null);
        when(magazineRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(magazine));

        magazineService.update(1L, new AdminMagazineCommand(
                null, new ImageCommand(null, newBannerId), null, null));

        verify(mediaPort).reconcileBindings(
                List.of(new MediaAssetUse(newBannerId, MediaAssetPurpose.MAGAZINE_BANNER)),
                List.of(new MediaAssetUse(oldBannerId, MediaAssetPurpose.MAGAZINE_BANNER)));
        assertThat(magazine.getBannerKey()).isNull();
        assertThat(magazine.getBannerImageAssetId()).isEqualTo(newBannerId);
        assertThat(magazine.getThumbnailKey()).isEqualTo("magazines/thumb.jpg");
    }

    @Test
    void 두_슬롯중_하나의_media_검증이_실패하면_Aggregate를_변경하지_않는다() {
        UUID oldBannerId = UUID.randomUUID();
        UUID oldThumbnailId = UUID.randomUUID();
        Magazine magazine = magazine(null, oldBannerId, null, oldThumbnailId);
        when(magazineRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(magazine));
        doThrow(new BusinessException(MediaErrorCode.INVALID_STATE))
                .when(mediaPort).reconcileBindings(anyCollection(), anyCollection());

        assertThatThrownBy(() -> magazineService.update(1L, new AdminMagazineCommand(
                "반영되면 안 됨", new ImageCommand(null, UUID.randomUUID()),
                new ImageCommand(null, UUID.randomUUID()), null)))
                .isInstanceOf(BusinessException.class);

        assertThat(magazine.getTitle()).isEqualTo("원래 제목");
        assertThat(magazine.getBannerImageAssetId()).isEqualTo(oldBannerId);
        assertThat(magazine.getThumbnailImageAssetId()).isEqualTo(oldThumbnailId);
    }

    @Test
    void 새_legacy_key로_교체하면_기존_asset만_retire하고_ID를_비운다() {
        UUID oldBannerId = UUID.randomUUID();
        Magazine magazine = magazine(null, oldBannerId, "magazines/thumb.jpg", null);
        when(magazineRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(magazine));

        magazineService.update(1L, new AdminMagazineCommand(
                null, new ImageCommand("magazines/new-banner.jpg", null), null, null));

        verify(mediaPort).reconcileBindings(
                List.of(),
                List.of(new MediaAssetUse(oldBannerId, MediaAssetPurpose.MAGAZINE_BANNER)));
        assertThat(magazine.getBannerImageAssetId()).isNull();
        assertThat(magazine.getBannerKey()).isEqualTo("magazines/new-banner.jpg");
    }

    @Test
    void 같은_legacy_key_재전달은_backfill_asset을_분리하지_않는다() {
        UUID bannerId = UUID.randomUUID();
        Magazine magazine = magazine("magazines/banner.jpg", bannerId, "magazines/thumb.jpg", null);
        when(magazineRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(magazine));

        magazineService.update(1L, new AdminMagazineCommand(
                null, new ImageCommand("magazines/banner.jpg", null), null, null));

        assertThat(magazine.getBannerImageAssetId()).isEqualTo(bannerId);
        verify(mediaPort, never()).reconcileBindings(anyCollection(), anyCollection());
    }

    @Test
    void soft_delete는_Aggregate를_잠그고_asset_binding을_유지한다() {
        Magazine magazine = magazine(null, UUID.randomUUID(), null, UUID.randomUUID());
        when(magazineRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(magazine));

        magazineService.delete(1L);

        verify(magazineRepository).findByIdForUpdate(1L);
        verify(magazineRepository).delete(magazine);
        verify(mediaPort, never()).reconcileBindings(anyCollection(), anyCollection());
    }

    @Test
    void 배너_API는_배너_role만_한번에_조회한다() {
        UUID bannerId = UUID.randomUUID();
        UUID thumbnailId = UUID.randomUUID();
        Magazine magazine = magazine(null, bannerId, null, thumbnailId);
        MediaImageRequest bannerRequest = request(bannerId, MediaImageRole.MAGAZINE_BANNER);
        MediaImage image = readyImage(bannerId, MediaImageRole.MAGAZINE_BANNER);
        when(magazineRepository.findAllByOrderByIdDesc(PageRequest.of(0, 5)))
                .thenReturn(List.of(magazine));
        when(mediaPort.findImages(List.of(bannerRequest))).thenReturn(Map.of(bannerRequest, image));

        var response = magazineService.getBanners();

        assertThat(response.banners()).singleElement().satisfies(banner -> {
            assertThat(banner.bannerImage()).isEqualTo(image);
            assertThat(banner.bannerImageUrl()).isEqualTo(image.defaultSource().url());
        });
        verify(mediaPort).findImages(List.of(bannerRequest));
    }

    @Test
    void 목록은_PROCESSING_FAILED와_lookup_mismatch에서_legacy로_우회하지_않는다() {
        UUID bannerId = UUID.randomUUID();
        UUID thumbnailId = UUID.randomUUID();
        Magazine magazine = magazine("magazines/old-banner.jpg", bannerId,
                "magazines/old-thumbnail.jpg", thumbnailId);
        MediaImageRequest bannerRequest = request(bannerId, MediaImageRole.MAGAZINE_BANNER);
        MediaImageRequest thumbnailRequest = request(thumbnailId, MediaImageRole.MAGAZINE_THUMBNAIL);
        MediaImage failed = new MediaImage(
                bannerId, MediaImageRole.MAGAZINE_BANNER, MediaImageStatus.FAILED, null, List.of());
        MediaImage processing = new MediaImage(
                thumbnailId, MediaImageRole.MAGAZINE_THUMBNAIL,
                MediaImageStatus.PROCESSING, null, List.of());
        when(magazineRepository.findAllByOrderByIdDesc(PageRequest.of(0, 2)))
                .thenReturn(List.of(magazine));
        when(mediaPort.findImages(List.of(bannerRequest, thumbnailRequest)))
                .thenReturn(Map.of(bannerRequest, failed, thumbnailRequest, processing));

        var response = magazineService.getMagazines(null, 1).magazines().getFirst();
        when(mediaPort.findImages(List.of(bannerRequest, thumbnailRequest))).thenReturn(Map.of());
        var mismatch = magazineService.getMagazines(null, 1).magazines().getFirst();

        assertThat(response.bannerImageUrl()).isNull();
        assertThat(response.thumbnailImageUrl()).isNull();
        assertThat(response.bannerImage()).isEqualTo(failed);
        assertThat(response.thumbnailImage()).isEqualTo(processing);
        assertThat(mismatch.bannerImageUrl()).isNull();
        assertThat(mismatch.thumbnailImageUrl()).isNull();
        assertThat(mismatch.bannerImage()).isNull();
        assertThat(mismatch.thumbnailImage()).isNull();
    }

    @Test
    void 두_슬롯의_중복_asset은_MEDIA_008을_유지하고_저장하지_않는다() {
        UUID assetId = UUID.randomUUID();
        doThrow(new BusinessException(MediaErrorCode.DUPLICATE_ASSET))
                .when(mediaPort).reconcileBindings(anyCollection(), anyCollection());

        assertThatThrownBy(() -> magazineService.create(new AdminMagazineCommand(
                "중복", new ImageCommand(null, assetId), new ImageCommand(null, assetId),
                "https://www.instagram.com/p/test/")))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(MediaErrorCode.DUPLICATE_ASSET));

        verify(magazineRepository, never()).save(any(Magazine.class));
    }

    private Magazine magazine(String bannerKey, UUID bannerId, String thumbnailKey, UUID thumbnailId) {
        Magazine magazine = Magazine.create(
                "원래 제목", bannerKey, bannerId, thumbnailKey, thumbnailId,
                "https://www.instagram.com/p/test/");
        ReflectionTestUtils.setField(magazine, "id", 1L);
        ReflectionTestUtils.setField(magazine, "createdAt", LocalDateTime.of(2026, 8, 31, 1, 0));
        return magazine;
    }

    private MediaImageRequest request(UUID assetId, MediaImageRole role) {
        return new MediaImageRequest(assetId, role);
    }

    private MediaImage readyImage(UUID assetId, MediaImageRole role) {
        String url = "https://cdn.hashi.test/" + role.name().toLowerCase() + "/96.webp";
        return new MediaImage(
                assetId, role, MediaImageStatus.READY,
                new Source(url, 96, 96, "image/webp"),
                List.of(new SourceSet("image/webp", List.of(new Candidate(url, 96, 96)))));
    }
}
