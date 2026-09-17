package org.sopt.hashi.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sopt.hashi.auth.CurrentUserProvider;
import org.sopt.hashi.media.ImageReference;
import org.sopt.hashi.media.MediaImage;
import org.sopt.hashi.media.MediaImageRequest;
import org.sopt.hashi.media.MediaImageRole;
import org.sopt.hashi.media.MediaImageStatus;
import org.sopt.hashi.media.MediaPort;
import org.sopt.hashi.point.PointPort;
import org.sopt.hashi.reservation.AdminReservationInfo;
import org.sopt.hashi.reservation.domain.Reservation;
import org.sopt.hashi.reservation.domain.ReservationRepository;
import org.sopt.hashi.reservation.dto.ReservationDetailResponse;
import org.sopt.hashi.reservation.dto.ReservationListResponse;
import org.sopt.hashi.restaurant.RestaurantDetailInfo;
import org.sopt.hashi.restaurant.RestaurantInfo;
import org.sopt.hashi.restaurant.RestaurantPort;
import org.sopt.hashi.user.UserPort;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private RestaurantPort restaurantPort;

    @Mock
    private MediaPort mediaPort;

    @Mock
    private PointPort pointPort;

    @Mock
    private UserPort userPort;

    @Mock
    private CurrentUserProvider currentUserProvider;

    @Test
    void 예약_목록은_페이지에_포함된_식당_이미지만_한번에_bulk_조회한다() {
        ReservationService service = createService();
        UUID firstAssetId = UUID.randomUUID();
        UUID secondAssetId = UUID.randomUUID();
        UUID lookaheadAssetId = UUID.randomUUID();
        Reservation first = standardReservation(101L, 10L);
        Reservation second = standardReservation(100L, 20L);
        Reservation lookahead = standardReservation(99L, 30L);
        given(currentUserProvider.currentUserId()).willReturn(7L);
        given(reservationRepository.findByUserIdOrderByIdDesc(eq(7L), any(Pageable.class)))
                .willReturn(List.of(first, second, lookahead));
        given(reservationRepository.countByUserId(7L)).willReturn(3L);
        given(restaurantPort.findSummaries(ArgumentMatchers.argThat(
                ids -> Set.copyOf(ids).equals(Set.of(10L, 20L)))))
                .willReturn(List.of(
                        restaurantInfo(10L, firstAssetId, "https://legacy/first.jpg"),
                        restaurantInfo(20L, secondAssetId, "https://legacy/second.jpg")
                ));
        MediaImage firstImage = readyImage(firstAssetId, "https://cdn/first.webp");
        MediaImage secondImage = readyImage(secondAssetId, "https://cdn/second.webp");
        given(mediaPort.findImages(any())).willReturn(Map.of(
                request(firstAssetId), firstImage,
                request(secondAssetId), secondImage
        ));

        ReservationListResponse response = service.getMyReservations(null, 2, null);

        assertThat(response.reservations()).hasSize(2);
        assertThat(response.hasNext()).isTrue();
        assertThat(response.nextCursor()).isEqualTo(100L);
        assertThat(response.reservations().getFirst().restaurantImageUrl())
                .isEqualTo("https://cdn/first.webp");
        assertThat(response.reservations().getFirst().restaurantThumbnailImage())
                .isEqualTo(firstImage);
        verify(mediaPort).findImages(ArgumentMatchers.argThat(
                requests -> Set.copyOf(requests).equals(Set.of(
                        request(firstAssetId), request(secondAssetId)))
                        && requests.stream().noneMatch(
                        request -> request.assetId().equals(lookaheadAssetId))));
    }

    @Test
    void 어디든_예약만_있으면_식당과_media를_조회하지_않는다() {
        ReservationService service = createService();
        Reservation anywhere = anywhereReservation(101L);
        given(currentUserProvider.currentUserId()).willReturn(7L);
        given(reservationRepository.findByUserIdOrderByIdDesc(eq(7L), any(Pageable.class)))
                .willReturn(List.of(anywhere));
        given(reservationRepository.countByUserId(7L)).willReturn(1L);

        ReservationListResponse response = service.getMyReservations(null, 10, null);

        assertThat(response.reservations()).singleElement()
                .satisfies(item -> {
                    assertThat(item.restaurantName()).isEqualTo("긴자 미등록 식당");
                    assertThat(item.restaurantAddress()).isEqualTo("도쿄도 주오구 긴자");
                    assertThat(item.restaurantImageUrl()).isNull();
                    assertThat(item.restaurantThumbnailImage()).isNull();
                });
        verifyNoInteractions(restaurantPort, mediaPort);
    }

    @Test
    void 예약_상세의_PROCESSING_asset은_legacy_URL로_우회하지_않는다() {
        ReservationService service = createService();
        UUID assetId = UUID.randomUUID();
        Reservation reservation = standardReservation(101L, 10L);
        MediaImage processing = statusImage(assetId, MediaImageStatus.PROCESSING);
        given(currentUserProvider.currentUserId()).willReturn(7L);
        given(reservationRepository.findById(101L)).willReturn(Optional.of(reservation));
        given(restaurantPort.findDetailById(10L)).willReturn(Optional.of(
                new RestaurantDetailInfo(
                        10L,
                        "스시야",
                        "寿司屋",
                        "도쿄",
                        new ImageReference(assetId, "https://legacy/original.jpg"))));
        given(mediaPort.findImages(any())).willReturn(Map.of(request(assetId), processing));

        ReservationDetailResponse response = service.getMyReservation(101L);

        assertThat(response.restaurantImageUrl()).isNull();
        assertThat(response.restaurantThumbnailImage()).isEqualTo(processing);
        verify(mediaPort).findImages(ArgumentMatchers.argThat(
                requests -> List.copyOf(requests).equals(List.of(request(assetId)))));
    }

    @Test
    void 어드민_예약_조회는_media를_해석하지_않고_ImageReference를_전달한다() {
        ReservationService service = createService();
        UUID assetId = UUID.randomUUID();
        Reservation reservation = standardReservation(101L, 10L);
        ImageReference reference = new ImageReference(assetId, "https://legacy/original.jpg");
        given(reservationRepository.findAll(any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(reservation)));
        given(restaurantPort.findSummaries(Set.of(10L)))
                .willReturn(List.of(new RestaurantInfo(10L, "스시야", "도쿄", reference)));

        Page<AdminReservationInfo> response = service.findPageByAdmin(null, 0, 10);

        assertThat(response.getContent()).singleElement()
                .satisfies(info -> assertThat(info.restaurantImageReference()).isEqualTo(reference));
        verifyNoInteractions(mediaPort);
    }

    private ReservationService createService() {
        return new ReservationService(
                reservationRepository,
                restaurantPort,
                mediaPort,
                pointPort,
                userPort,
                currentUserProvider);
    }

    private Reservation standardReservation(Long id, Long restaurantId) {
        Reservation reservation = Reservation.standard(
                7L,
                "예약자",
                restaurantId,
                LocalDateTime.of(2026, 9, 1, 18, 0),
                2,
                0,
                0,
                null,
                0L,
                4_000L);
        ReflectionTestUtils.setField(reservation, "id", id);
        return reservation;
    }

    private Reservation anywhereReservation(Long id) {
        Reservation reservation = Reservation.anywhere(
                7L,
                "예약자",
                "긴자 미등록 식당",
                "도쿄도 주오구 긴자",
                LocalDateTime.of(2026, 9, 1, 18, 0),
                2,
                0,
                0,
                null,
                0L,
                4_000L);
        ReflectionTestUtils.setField(reservation, "id", id);
        return reservation;
    }

    private RestaurantInfo restaurantInfo(Long id, UUID assetId, String legacyUrl) {
        return new RestaurantInfo(
                id,
                "식당 " + id,
                "도쿄",
                new ImageReference(assetId, legacyUrl));
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
