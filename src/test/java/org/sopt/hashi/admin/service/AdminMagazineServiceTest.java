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
import org.sopt.hashi.admin.dto.UpdateMagazineRequest;
import org.sopt.hashi.magazine.AdminMagazineCommand;
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
                "매거진", null, banner.assetId(), null, thumbnail.assetId(),
                "https://www.instagram.com/p/test/"));

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
                        "https://www.instagram.com/p/test/", LocalDateTime.now()));

        AdminMagazineResponse response = service.create(new CreateMagazineRequest(
                "매거진", "magazines/banner.jpg", "magazines/thumbnail.jpg",
                "https://www.instagram.com/p/test/"));

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
                null, null, banner.assetId(), null, null, null));

        ArgumentCaptor<AdminMagazineCommand> command = ArgumentCaptor.forClass(AdminMagazineCommand.class);
        verify(magazinePort).updateByAdmin(eq(1L), command.capture());
        assertThat(command.getValue().bannerImage().imageAssetId()).isEqualTo(banner.assetId());
        assertThat(command.getValue().thumbnailImage()).isNull();
    }

    private MagazineInfo info(MediaImage banner, MediaImage thumbnail) {
        return new MagazineInfo(
                1L, "매거진", banner.defaultSource().url(), banner,
                thumbnail.defaultSource().url(), thumbnail,
                "https://www.instagram.com/p/test/", LocalDateTime.now());
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
