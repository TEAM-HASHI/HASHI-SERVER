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
import org.sopt.hashi.magazine.MagazineCardNewsInfo;
import org.sopt.hashi.magazine.code.MagazineErrorCode;
import org.sopt.hashi.magazine.domain.CardNewsSource;
import org.sopt.hashi.magazine.domain.Magazine;
import org.sopt.hashi.magazine.domain.MagazineCardNews;
import org.sopt.hashi.magazine.domain.MagazineMetaRepository;
import org.sopt.hashi.magazine.domain.MagazineReactionRepository;
import org.sopt.hashi.magazine.domain.MagazineRepository;
import org.sopt.hashi.magazine.dto.MagazineDetailResponse.CardNewsImageResponse;
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
import org.sopt.hashi.restaurant.RestaurantDetailInfo;
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

        var response = magazineService.create(command(
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
        var response = magazineService.create(command(
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

        magazineService.update(1L, command(
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

        magazineService.update(1L, command(
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

        assertThatThrownBy(() -> magazineService.update(1L, command(
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

        magazineService.update(1L, command(
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

        magazineService.update(1L, command(
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
        assertThat(magazine.isDeleted()).isTrue();
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

        assertThatThrownBy(() -> magazineService.create(command(
                "중복", new ImageCommand(null, assetId), new ImageCommand(null, assetId),
                "https://www.instagram.com/p/test/")))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(MediaErrorCode.DUPLICATE_ASSET));

        verify(magazineRepository, never()).save(any(Magazine.class));
    }

    @Test
    void 등록은_카드뉴스_asset을_한번에_claim하고_상세_화면_데이터를_순서대로_저장한다() {
        UUID firstAssetId = UUID.randomUUID();
        UUID secondAssetId = UUID.randomUUID();
        MediaImageRequest firstRequest = request(firstAssetId, MediaImageRole.MAGAZINE_CARD_NEWS);
        MediaImageRequest secondRequest = request(secondAssetId, MediaImageRole.MAGAZINE_CARD_NEWS);
        MediaImage firstImage = readyImage(firstAssetId, MediaImageRole.MAGAZINE_CARD_NEWS);
        when(mediaPort.findImages(List.of(firstRequest, secondRequest)))
                .thenReturn(Map.of(firstRequest, firstImage));
        activeRestaurants(List.of(1002L, 1001L), 1002L, 1001L);

        var response = magazineService.create(detailCommand(
                "본문",
                List.of(
                        new ImageCommand(null, firstAssetId),
                        new ImageCommand("magazines/card-2.jpg", null),
                        new ImageCommand(null, secondAssetId)),
                List.of("이자카야", "퇴근길", "이자카야"),
                List.of(1002L, 1001L)));

        verify(mediaPort).reconcileBindings(
                List.of(
                        new MediaAssetUse(firstAssetId, MediaAssetPurpose.MAGAZINE_CARD_NEWS),
                        new MediaAssetUse(secondAssetId, MediaAssetPurpose.MAGAZINE_CARD_NEWS)),
                List.of());
        verify(mediaPort).findImages(List.of(firstRequest, secondRequest));
        assertThat(response.content()).isEqualTo("본문");
        assertThat(response.hashtags()).containsExactly("이자카야", "퇴근길");
        assertThat(response.restaurantIds()).containsExactly(1002L, 1001L);
        assertThat(response.cardNews())
                .extracting(MagazineCardNewsInfo::displayOrder)
                .containsExactly(1, 2, 3);
        assertThat(response.cardNews().get(0).image()).isEqualTo(firstImage);
        assertThat(response.cardNews().get(0).legacyUrl()).isNull();
        assertThat(response.cardNews().get(1).image()).isNull();
        assertThat(response.cardNews().get(1).legacyUrl())
                .isEqualTo("https://cdn.hashi.test/magazines/card-2.jpg");
        // 조회되지 않은 asset도 legacy 주소로 우회하지 않는다
        assertThat(response.cardNews().get(2).image()).isNull();
        assertThat(response.cardNews().get(2).legacyUrl()).isNull();
    }

    @Test
    void 없는_식당이나_삭제된_식당을_연결하면_저장하지_않는다() {
        activeRestaurants(List.of(1001L, 999999L), 1001L);

        assertThatThrownBy(() -> magazineService.create(detailCommand(
                null, List.of(new ImageCommand(null, UUID.randomUUID())), null,
                List.of(1001L, 999999L))))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(MagazineErrorCode.RESTAURANT_NOT_FOUND));

        verify(mediaPort, never()).reconcileBindings(anyCollection(), anyCollection());
        verify(magazineRepository, never()).save(any(Magazine.class));
    }

    @Test
    void 같은_asset을_카드뉴스_두_장에_쓰면_media를_부르기_전에_거절한다() {
        UUID assetId = UUID.randomUUID();

        assertThatThrownBy(() -> magazineService.create(detailCommand(
                null,
                List.of(new ImageCommand(null, assetId), new ImageCommand(null, assetId)),
                null, null)))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(MagazineErrorCode.CARD_NEWS_ASSET_DUPLICATED));

        verify(mediaPort, never()).reconcileBindings(anyCollection(), anyCollection());
        verify(magazineRepository, never()).save(any(Magazine.class));
    }

    @Test
    void 카드뉴스_교체는_같은_이미지의_행을_재사용하고_새_asset만_claim_빠진_asset만_retire한다() {
        UUID removedAssetId = UUID.randomUUID();
        UUID retainedAssetId = UUID.randomUUID();
        UUID newAssetId = UUID.randomUUID();
        Magazine magazine = magazine("magazines/banner.jpg", null, "magazines/thumb.jpg", null);
        List<MagazineCardNews> existing = attachCardNews(magazine,
                legacy("magazines/card-1.jpg"),
                asset(removedAssetId),
                asset(retainedAssetId),
                legacy("magazines/card-4.jpg"));
        MagazineCardNews removedLegacy = existing.get(0);
        MagazineCardNews removedAsset = existing.get(1);
        MagazineCardNews retainedAsset = existing.get(2);
        MagazineCardNews retainedLegacy = existing.get(3);
        when(magazineRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(magazine));

        magazineService.update(1L, detailCommand(
                null,
                List.of(
                        new ImageCommand(null, retainedAssetId),
                        new ImageCommand("magazines/card-new.jpg", null),
                        new ImageCommand(null, newAssetId),
                        new ImageCommand("magazines/card-4.jpg", null)),
                null, null));

        verify(mediaPort).reconcileBindings(
                List.of(new MediaAssetUse(newAssetId, MediaAssetPurpose.MAGAZINE_CARD_NEWS)),
                List.of(new MediaAssetUse(removedAssetId, MediaAssetPurpose.MAGAZINE_CARD_NEWS)));
        List<MagazineCardNews> ordered = magazine.getOrderedCardNews();
        assertThat(ordered).hasSize(4);
        assertThat(ordered.get(0)).isSameAs(retainedAsset);
        assertThat(ordered.get(1).getFileKey()).isEqualTo("magazines/card-new.jpg");
        assertThat(ordered.get(1).getId()).isNull();
        assertThat(ordered.get(2).getImageAssetId()).isEqualTo(newAssetId);
        assertThat(ordered.get(3)).isSameAs(retainedLegacy);
        assertThat(ordered)
                .extracting(MagazineCardNews::getDisplayOrder)
                .containsExactly(1, 2, 3, 4);
        assertThat(magazine.getCardNews()).doesNotContain(removedLegacy, removedAsset);
        verify(magazineRepository).flush();
    }

    @Test
    void 같은_legacy_key가_여러_장이면_보낸_수만큼만_기존_행을_재사용한다() {
        Magazine magazine = magazine("magazines/banner.jpg", null, "magazines/thumb.jpg", null);
        MagazineCardNews first = attachCardNews(magazine,
                legacy("magazines/same.jpg"),
                legacy("magazines/same.jpg")).get(0);
        when(magazineRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(magazine));

        magazineService.update(1L, detailCommand(
                null, List.of(new ImageCommand("magazines/same.jpg", null)), null, null));

        assertThat(magazine.getCardNews()).containsExactly(first);
        verify(mediaPort, never()).reconcileBindings(anyCollection(), anyCollection());
    }

    @Test
    void 상세_화면_데이터를_보내지_않은_수정은_기존_값과_asset을_유지한다() {
        UUID assetId = UUID.randomUUID();
        Magazine magazine = detailMagazine(assetId);
        when(magazineRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(magazine));

        var response = magazineService.update(1L, command("제목만 변경", null, null, null));

        verify(mediaPort, never()).reconcileBindings(anyCollection(), anyCollection());
        verify(restaurantPort, never()).findActiveDetails(anyCollection());
        assertThat(response.title()).isEqualTo("제목만 변경");
        assertThat(response.content()).isEqualTo("원래 본문");
        assertThat(response.cardNews()).hasSize(1);
        assertThat(response.hashtags()).containsExactly("이자카야");
        assertThat(response.restaurantIds()).containsExactly(1001L, 1002L);
    }

    @Test
    void 빈_목록과_빈_본문은_모두_지우고_카드뉴스_asset을_retire한다() {
        UUID assetId = UUID.randomUUID();
        Magazine magazine = detailMagazine(assetId);
        when(magazineRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(magazine));

        var response = magazineService.update(1L, detailCommandForUpdate(
                "", List.of(), List.of(), List.of()));

        verify(mediaPort).reconcileBindings(
                List.of(),
                List.of(new MediaAssetUse(assetId, MediaAssetPurpose.MAGAZINE_CARD_NEWS)));
        verify(restaurantPort, never()).findActiveDetails(anyCollection());
        assertThat(response.content()).isNull();
        assertThat(response.cardNews()).isEmpty();
        assertThat(response.hashtags()).isEmpty();
        assertThat(response.restaurantIds()).isEmpty();
    }

    @Test
    void 연결_식당_교체는_이미_연결된_행을_재사용하고_보낸_순서로_정렬한다() {
        Magazine magazine = detailMagazine(UUID.randomUUID());
        var firstBefore = magazine.getRestaurants().get(0);
        when(magazineRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(magazine));
        activeRestaurants(List.of(1003L, 1001L), 1003L, 1001L);

        var response = magazineService.update(1L, detailCommandForUpdate(
                null, null, null, List.of(1003L, 1001L)));

        assertThat(response.restaurantIds()).containsExactly(1003L, 1001L);
        assertThat(magazine.getRestaurants()).hasSize(2).contains(firstBefore);
        assertThat(firstBefore.getRestaurantId()).isEqualTo(1001L);
        assertThat(firstBefore.getDisplayOrder()).isEqualTo(2);
    }

    @Test
    void 상세는_카드뉴스_asset을_한번에_조회하고_wrapper와_기존_URL_목록을_구분해_내린다() {
        UUID readyAssetId = UUID.randomUUID();
        UUID processingAssetId = UUID.randomUUID();
        Magazine magazine = magazine("magazines/banner.jpg", null, "magazines/thumb.jpg", null);
        attachCardNews(magazine,
                legacy("magazines/card-1.jpg"),
                asset(processingAssetId),
                asset(readyAssetId));
        MediaImageRequest processingRequest = request(processingAssetId, MediaImageRole.MAGAZINE_CARD_NEWS);
        MediaImageRequest readyRequest = request(readyAssetId, MediaImageRole.MAGAZINE_CARD_NEWS);
        MediaImage processing = new MediaImage(
                processingAssetId, MediaImageRole.MAGAZINE_CARD_NEWS,
                MediaImageStatus.PROCESSING, null, List.of());
        MediaImage ready = readyImage(readyAssetId, MediaImageRole.MAGAZINE_CARD_NEWS);
        when(magazineRepository.findById(1L)).thenReturn(Optional.of(magazine));
        when(restaurantPort.findActiveDetails(List.of())).thenReturn(List.of());
        when(mediaPort.findImages(List.of(processingRequest, readyRequest)))
                .thenReturn(Map.of(processingRequest, processing, readyRequest, ready));

        var response = magazineService.getDetail(1L);

        verify(mediaPort).findImages(List.of(processingRequest, readyRequest));
        assertThat(response.cardNewsImageUrls()).containsExactly(
                "https://cdn.hashi.test/magazines/card-1.jpg",
                ready.defaultSource().url());
        assertThat(response.cardNewsImages()).containsExactly(
                new CardNewsImageResponse(
                        11L, 1, null, "https://cdn.hashi.test/magazines/card-1.jpg"),
                new CardNewsImageResponse(12L, 2, processing, null),
                new CardNewsImageResponse(13L, 3, ready, null));
    }

    @Test
    void 카드뉴스가_모두_legacy면_상세에서_media를_조회하지_않는다() {
        Magazine magazine = magazine("magazines/banner.jpg", null, "magazines/thumb.jpg", null);
        attachCardNews(magazine, legacy("magazines/card-1.jpg"));
        when(magazineRepository.findById(1L)).thenReturn(Optional.of(magazine));
        when(restaurantPort.findActiveDetails(List.of())).thenReturn(List.of());

        var response = magazineService.getDetail(1L);

        verify(mediaPort, never()).findImages(anyCollection());
        assertThat(response.cardNewsImages()).singleElement()
                .satisfies(image -> assertThat(image.legacyUrl())
                        .isEqualTo("https://cdn.hashi.test/magazines/card-1.jpg"));
    }

    private AdminMagazineCommand command(
            String title, ImageCommand bannerImage, ImageCommand thumbnailImage,
            String instagramRedirectUrl
    ) {
        return new AdminMagazineCommand(
                title, bannerImage, thumbnailImage, instagramRedirectUrl,
                null, null, null, null);
    }

    private AdminMagazineCommand detailCommand(
            String content, List<ImageCommand> cardNews, List<String> hashtags,
            List<Long> restaurantIds
    ) {
        return new AdminMagazineCommand(
                "상세 매거진",
                new ImageCommand("magazines/banner.jpg", null),
                new ImageCommand("magazines/thumbnail.jpg", null),
                "https://www.instagram.com/p/test/",
                content, cardNews, hashtags, restaurantIds);
    }

    private AdminMagazineCommand detailCommandForUpdate(
            String content, List<ImageCommand> cardNews, List<String> hashtags,
            List<Long> restaurantIds
    ) {
        return new AdminMagazineCommand(
                null, null, null, null, content, cardNews, hashtags, restaurantIds);
    }

    // 카드뉴스 asset 1장·해시태그 1개·연결 식당 [1001, 1002]·본문을 가진 매거진
    private Magazine detailMagazine(UUID cardNewsAssetId) {
        Magazine magazine = magazine("magazines/banner.jpg", null, "magazines/thumb.jpg", null);
        magazine.update(
                null, "magazines/banner.jpg", null, "magazines/thumb.jpg", null, null,
                "원래 본문");
        attachCardNews(magazine, asset(cardNewsAssetId));
        magazine.replaceHashtags(List.of("이자카야"));
        magazine.replaceRestaurants(List.of(1001L, 1002L));
        return magazine;
    }

    private CardNewsSource legacy(String fileKey) {
        return new CardNewsSource(fileKey, null);
    }

    private CardNewsSource asset(UUID imageAssetId) {
        return new CardNewsSource(null, imageAssetId);
    }

    // 저장된 카드뉴스처럼 보이게 순서대로 붙이고 id를 11부터 매긴다
    private List<MagazineCardNews> attachCardNews(Magazine magazine, CardNewsSource... sources) {
        magazine.replaceCardNews(magazine.planCardNewsReplacement(List.of(sources)));
        List<MagazineCardNews> attached = magazine.getOrderedCardNews();
        for (int index = 0; index < attached.size(); index++) {
            ReflectionTestUtils.setField(attached.get(index), "id", 11L + index);
        }
        return attached;
    }

    private void activeRestaurants(List<Long> requestedIds, Long... activeIds) {
        List<RestaurantDetailInfo> details = java.util.Arrays.stream(activeIds)
                .map(id -> {
                    RestaurantDetailInfo detail = org.mockito.Mockito.mock(RestaurantDetailInfo.class);
                    when(detail.id()).thenReturn(id);
                    return detail;
                })
                .toList();
        when(restaurantPort.findActiveDetails(requestedIds)).thenReturn(details);
    }

    private Magazine magazine(String bannerKey, UUID bannerId, String thumbnailKey, UUID thumbnailId) {
        Magazine magazine = Magazine.create(
                "원래 제목", bannerKey, bannerId, thumbnailKey, thumbnailId,
                "https://www.instagram.com/p/test/", null);
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
