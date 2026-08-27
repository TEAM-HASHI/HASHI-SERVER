package org.sopt.hashi.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sopt.hashi.admin.dto.AdminReservationListResponse;
import org.sopt.hashi.admin.dto.AdminReservationResponse;
import org.sopt.hashi.media.ImageReference;
import org.sopt.hashi.media.MediaImage;
import org.sopt.hashi.media.MediaImageRequest;
import org.sopt.hashi.media.MediaImageRole;
import org.sopt.hashi.media.MediaImageStatus;
import org.sopt.hashi.media.MediaPort;
import org.sopt.hashi.reservation.AdminReservationInfo;
import org.sopt.hashi.reservation.PaymentStatus;
import org.sopt.hashi.reservation.ReservationPort;
import org.sopt.hashi.reservation.ReservationStatus;
import org.sopt.hashi.reservation.ReservationType;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class AdminReservationServiceTest {

    @Mock
    private ReservationPort reservationPort;

    @Mock
    private MediaPort mediaPort;

    @Test
    void 어드민_예약_목록은_READY와_상태_이미지를_한번에_projection한다() {
        AdminReservationService service = new AdminReservationService(reservationPort, mediaPort);
        UUID readyAssetId = UUID.randomUUID();
        UUID processingAssetId = UUID.randomUUID();
        UUID missingAssetId = UUID.randomUUID();
        List<AdminReservationInfo> infos = List.of(
                info(101L, new ImageReference(readyAssetId, "https://legacy/ready.jpg")),
                info(102L, new ImageReference(processingAssetId, "https://legacy/processing.jpg")),
                info(103L, new ImageReference(missingAssetId, "https://legacy/missing.jpg"))
        );
        given(reservationPort.findPageByAdmin(null, 0, 10))
                .willReturn(new PageImpl<>(infos, PageRequest.of(0, 10), infos.size()));
        MediaImage ready = readyImage(readyAssetId, "https://cdn/ready.webp");
        MediaImage processing = statusImage(processingAssetId, MediaImageStatus.PROCESSING);
        given(mediaPort.findImages(any())).willReturn(Map.of(
                request(readyAssetId), ready,
                request(processingAssetId), processing
        ));

        AdminReservationListResponse response = service.getReservations(null, 0, 10);

        assertThat(response.reservations()).hasSize(3);
        assertThat(response.reservations().get(0).restaurantImageUrl())
                .isEqualTo("https://cdn/ready.webp");
        assertThat(response.reservations().get(0).restaurantThumbnailImage()).isEqualTo(ready);
        assertThat(response.reservations().get(1).restaurantImageUrl()).isNull();
        assertThat(response.reservations().get(1).restaurantThumbnailImage()).isEqualTo(processing);
        assertThat(response.reservations().get(2).restaurantImageUrl()).isNull();
        assertThat(response.reservations().get(2).restaurantThumbnailImage()).isNull();
        verify(mediaPort).findImages(ArgumentMatchers.argThat(
                requests -> Set.copyOf(requests).equals(Set.of(
                        request(readyAssetId),
                        request(processingAssetId),
                        request(missingAssetId)))));
    }

    @Test
    void legacy_only_어드민_예약은_media를_조회하지_않고_기존_URL을_유지한다() {
        AdminReservationService service = new AdminReservationService(reservationPort, mediaPort);
        AdminReservationInfo info = info(
                101L, ImageReference.legacy("https://legacy/restaurant.jpg"));
        given(reservationPort.changeStatusByAdmin(101L, ReservationStatus.CONFIRMED))
                .willReturn(info);

        AdminReservationResponse response = service.changeStatus(
                101L, ReservationStatus.CONFIRMED);

        assertThat(response.restaurantImageUrl())
                .isEqualTo("https://legacy/restaurant.jpg");
        assertThat(response.restaurantThumbnailImage()).isNull();
        verifyNoInteractions(mediaPort);
    }

    private AdminReservationInfo info(Long reservationId, ImageReference imageReference) {
        return new AdminReservationInfo(
                reservationId,
                7L,
                ReservationType.STANDARD,
                "예약자",
                10L,
                "스시야",
                imageReference,
                "도쿄",
                LocalDateTime.of(2026, 9, 1, 18, 0),
                2,
                0,
                0,
                null,
                ReservationStatus.REQUESTED,
                PaymentStatus.PENDING,
                0L,
                BigDecimal.valueOf(4_000),
                2L);
    }

    private MediaImageRequest request(UUID assetId) {
        return new MediaImageRequest(assetId, MediaImageRole.RESTAURANT_THUMBNAIL);
    }

    private MediaImage readyImage(UUID assetId, String url) {
        MediaImage.Source source = new MediaImage.Source(url, 192, 192, "image/webp");
        return new MediaImage(
                assetId,
                MediaImageRole.RESTAURANT_THUMBNAIL,
                MediaImageStatus.READY,
                source,
                List.of(new MediaImage.SourceSet(
                        "image/webp",
                        List.of(new MediaImage.Candidate(url, 192, 192)))));
    }

    private MediaImage statusImage(UUID assetId, MediaImageStatus status) {
        return new MediaImage(
                assetId,
                MediaImageRole.RESTAURANT_THUMBNAIL,
                status,
                null,
                List.of());
    }
}
