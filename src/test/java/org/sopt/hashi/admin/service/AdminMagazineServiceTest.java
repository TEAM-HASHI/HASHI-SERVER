package org.sopt.hashi.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sopt.hashi.admin.dto.AdminMagazineResponse;
import org.sopt.hashi.admin.dto.CreateMagazineRequest;
import org.sopt.hashi.admin.dto.MagazineCardNewsRequest;
import org.sopt.hashi.admin.dto.UpdateMagazineRequest;
import org.sopt.hashi.magazine.AdminMagazineCommand;
import org.sopt.hashi.magazine.AdminMagazineCommand.ImageCommand;
import org.sopt.hashi.magazine.MagazineCardNewsInfo;
import org.sopt.hashi.magazine.MagazineInfo;
import org.sopt.hashi.magazine.MagazinePort;
import org.sopt.hashi.media.MediaImage;
import org.sopt.hashi.media.MediaImage.Candidate;
import org.sopt.hashi.media.MediaImage.Source;
import org.sopt.hashi.media.MediaImage.SourceSet;
import org.sopt.hashi.media.MediaImageRole;
import org.sopt.hashi.media.MediaImageStatus;

@ExtendWith(MockitoExtension.class)
class AdminMagazineServiceTest {

    private static final String INSTAGRAM_URL = "https://www.instagram.com/p/test/";

    @Mock
    private MagazinePort magazinePort;

    private AdminMagazineService service;

    @BeforeEach
    void setUp() {
        service = new AdminMagazineService(magazinePort);
    }

    @Test
    void asset_등록은_슬롯별_ID를_전달하고_최적화_이미지_응답을_보존한다() {
        MediaImage banner = image(MediaImageRole.MAGAZINE_BANNER, 780, 354);
        MediaImage thumbnail = image(MediaImageRole.MAGAZINE_THUMBNAIL, 312, 176);
        when(magazinePort.createByAdmin(any(AdminMagazineCommand.class)))
                .thenReturn(info(banner, thumbnail));

        AdminMagazineResponse response = service.create(new CreateMagazineRequest(
                "매거진", null, banner.assetId(), null, thumbnail.assetId(), INSTAGRAM_URL,
                null, null, null, null));

        ArgumentCaptor<AdminMagazineCommand> command = ArgumentCaptor.forClass(AdminMagazineCommand.class);
        verify(magazinePort).createByAdmin(command.capture());
        assertThat(command.getValue().bannerImage().imageAssetId()).isEqualTo(banner.assetId());
        assertThat(command.getValue().bannerImage().imageKey()).isNull();
        assertThat(command.getValue().thumbnailImage().imageAssetId()).isEqualTo(thumbnail.assetId());
        assertThat(response.bannerImage()).isEqualTo(banner);
        assertThat(response.thumbnailImage()).isEqualTo(thumbnail);
        assertThat(response.bannerImageUrl()).isEqualTo(banner.defaultSource().url());
    }

    @Test
    void 기존_key_등록도_같은_Port로_전달하고_legacy_응답을_유지한다() {
        when(magazinePort.createByAdmin(any(AdminMagazineCommand.class)))
                .thenReturn(new MagazineInfo(
                        1L, "매거진", "https://cdn.hashi.test/banner.jpg", null,
                        "https://cdn.hashi.test/thumbnail.jpg", null,
                        INSTAGRAM_URL, LocalDateTime.now(),
                        null, List.of(), List.of(), List.of()));

        AdminMagazineResponse response = service.create(new CreateMagazineRequest(
                "매거진", "magazines/banner.jpg", null, "magazines/thumbnail.jpg", null, INSTAGRAM_URL,
                null, null, null, null));

        ArgumentCaptor<AdminMagazineCommand> command = ArgumentCaptor.forClass(AdminMagazineCommand.class);
        verify(magazinePort).createByAdmin(command.capture());
        assertThat(command.getValue().bannerImage().imageKey()).isEqualTo("magazines/banner.jpg");
        assertThat(command.getValue().bannerImage().imageAssetId()).isNull();
        assertThat(response.bannerImage()).isNull();
        assertThat(response.bannerImageUrl()).isEqualTo("https://cdn.hashi.test/banner.jpg");
    }

    @Test
    void PATCH에서_생략한_슬롯은_null_명령으로_전달해_기존값을_유지한다() {
        MediaImage banner = image(MediaImageRole.MAGAZINE_BANNER, 780, 354);
        MediaImage thumbnail = image(MediaImageRole.MAGAZINE_THUMBNAIL, 312, 176);
        when(magazinePort.updateByAdmin(eq(1L), any(AdminMagazineCommand.class)))
                .thenReturn(info(banner, thumbnail));

        service.update(1L, new UpdateMagazineRequest(
                null, null, banner.assetId(), null, null, null,
                null, null, null, null));

        ArgumentCaptor<AdminMagazineCommand> command = ArgumentCaptor.forClass(AdminMagazineCommand.class);
        verify(magazinePort).updateByAdmin(eq(1L), command.capture());
        assertThat(command.getValue().bannerImage().imageAssetId()).isEqualTo(banner.assetId());
        assertThat(command.getValue().thumbnailImage()).isNull();
    }

    @Test
    void 등록은_상세_화면_데이터를_순서대로_명령에_담아_전달한다() {
        UUID cardAssetId = UUID.randomUUID();
        when(magazinePort.createByAdmin(any(AdminMagazineCommand.class)))
                .thenReturn(info(
                        image(MediaImageRole.MAGAZINE_BANNER, 780, 354),
                        image(MediaImageRole.MAGAZINE_THUMBNAIL, 312, 176)));

        service.create(new CreateMagazineRequest(
                "매거진", "magazines/banner.jpg", null, "magazines/thumbnail.jpg", null, INSTAGRAM_URL,
                "본문",
                List.of(
                        new MagazineCardNewsRequest("magazines/card-1.jpg", null),
                        new MagazineCardNewsRequest(null, cardAssetId)),
                List.of("이자카야", "퇴근길"),
                List.of(1002L, 1001L)));

        ArgumentCaptor<AdminMagazineCommand> command = ArgumentCaptor.forClass(AdminMagazineCommand.class);
        verify(magazinePort).createByAdmin(command.capture());
        assertThat(command.getValue().content()).isEqualTo("본문");
        assertThat(command.getValue().cardNews()).containsExactly(
                new ImageCommand("magazines/card-1.jpg", null),
                new ImageCommand(null, cardAssetId));
        assertThat(command.getValue().hashtags()).containsExactly("이자카야", "퇴근길");
        assertThat(command.getValue().restaurantIds()).containsExactly(1002L, 1001L);
    }

    @Test
    void PATCH에서_생략한_상세_화면_데이터는_null로_빈_배열은_빈_목록으로_구분해_전달한다() {
        when(magazinePort.updateByAdmin(eq(1L), any(AdminMagazineCommand.class)))
                .thenReturn(info(
                        image(MediaImageRole.MAGAZINE_BANNER, 780, 354),
                        image(MediaImageRole.MAGAZINE_THUMBNAIL, 312, 176)));

        service.update(1L, new UpdateMagazineRequest(
                "제목", null, null, null, null, null,
                null, List.of(), null, List.of()));

        ArgumentCaptor<AdminMagazineCommand> command = ArgumentCaptor.forClass(AdminMagazineCommand.class);
        verify(magazinePort).updateByAdmin(eq(1L), command.capture());
        assertThat(command.getValue().content()).isNull();
        assertThat(command.getValue().cardNews()).isEmpty();
        assertThat(command.getValue().hashtags()).isNull();
        assertThat(command.getValue().restaurantIds()).isEmpty();
    }

    @Test
    void 응답은_카드뉴스_wrapper와_상세_화면_데이터를_그대로_옮긴다() {
        MediaImage cardImage = image(MediaImageRole.MAGAZINE_CARD_NEWS, 864, 1080);
        when(magazinePort.createByAdmin(any(AdminMagazineCommand.class)))
                .thenReturn(new MagazineInfo(
                        1L, "매거진", "https://cdn.hashi.test/banner.jpg", null,
                        "https://cdn.hashi.test/thumbnail.jpg", null,
                        INSTAGRAM_URL, LocalDateTime.now(),
                        "본문",
                        List.of(
                                new MagazineCardNewsInfo(
                                        11L, 1, null, "https://cdn.hashi.test/magazines/card-1.jpg"),
                                new MagazineCardNewsInfo(12L, 2, cardImage, null)),
                        List.of("이자카야"),
                        List.of(1002L, 1001L)));

        AdminMagazineResponse response = service.create(new CreateMagazineRequest(
                "매거진", "magazines/banner.jpg", null, "magazines/thumbnail.jpg", null, INSTAGRAM_URL,
                null, null, null, null));

        assertThat(response.content()).isEqualTo("본문");
        assertThat(response.hashtags()).containsExactly("이자카야");
        assertThat(response.restaurantIds()).containsExactly(1002L, 1001L);
        assertThat(response.cardNewsImages()).containsExactly(
                new AdminMagazineResponse.CardNewsImageResponse(
                        11L, 1, null, "https://cdn.hashi.test/magazines/card-1.jpg"),
                new AdminMagazineResponse.CardNewsImageResponse(12L, 2, cardImage, null));
    }

    private MagazineInfo info(MediaImage banner, MediaImage thumbnail) {
        return new MagazineInfo(
                1L, "매거진", banner.defaultSource().url(), banner,
                thumbnail.defaultSource().url(), thumbnail,
                INSTAGRAM_URL, LocalDateTime.now(),
                null, List.of(), List.of(), List.of());
    }

    private MediaImage image(MediaImageRole role, int width, int height) {
        UUID assetId = UUID.randomUUID();
        String url = "https://cdn.hashi.test/media/" + assetId + ".webp";
        return new MediaImage(
                assetId, role, MediaImageStatus.READY,
                new Source(url, width, height, "image/webp"),
                List.of(new SourceSet("image/webp", List.of(new Candidate(url, width, height)))));
    }
}
