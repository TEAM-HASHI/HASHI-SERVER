package org.sopt.hashi.admin.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.sopt.hashi.admin.dto.AdminReservationListResponse;
import org.sopt.hashi.admin.dto.AdminReservationResponse;
import org.sopt.hashi.admin.dto.AdminReservationUserResponse;
import org.sopt.hashi.media.ImageReference;
import org.sopt.hashi.media.MediaImage;
import org.sopt.hashi.media.MediaImageRequest;
import org.sopt.hashi.media.MediaImageRole;
import org.sopt.hashi.media.MediaImageStatus;
import org.sopt.hashi.media.MediaPort;
import org.sopt.hashi.reservation.AdminReservationInfo;
import org.sopt.hashi.reservation.ReservationPort;
import org.sopt.hashi.reservation.ReservationStatus;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;

/**
 * 어드민 예약 관리 — 진입점 모듈이라 도메인 로직 없이 {@link ReservationPort}로 위임하고
 * 응답 DTO 변환만 한다(architecture.md §9). 트랜잭션·포인트 복원·예약자 enrich는 reservation 소관.
 */
@Service
public class AdminReservationService {

    private final ReservationPort reservationPort;
    private final MediaPort mediaPort;

    public AdminReservationService(ReservationPort reservationPort, MediaPort mediaPort) {
        this.reservationPort = reservationPort;
        this.mediaPort = mediaPort;
    }

    /** 예약 상태 변경(자유 전이) — CANCELED 진입 시 포인트 복원 규칙은 reservation이 적용한다. */
    public AdminReservationResponse changeStatus(Long reservationId, ReservationStatus targetStatus) {
        AdminReservationInfo info = reservationPort.changeStatusByAdmin(reservationId, targetStatus);
        MediaProjection mediaProjection = loadThumbnailProjection(
                referenceList(info.restaurantImageReference()));
        return toResponse(info, mediaProjection);
    }

    /** 예약 목록 — 전체 사용자 대상 offset 페이지네이션(최신순), status가 null이면 전체. */
    public AdminReservationListResponse getReservations(ReservationStatus status, int page, int size) {
        Page<AdminReservationInfo> reservations = reservationPort.findPageByAdmin(status, page, size);
        MediaProjection mediaProjection = loadThumbnailProjection(
                reservations.getContent().stream()
                        .map(AdminReservationInfo::restaurantImageReference)
                        .toList());
        List<AdminReservationResponse> content = reservations.getContent().stream()
                .map(info -> toResponse(info, mediaProjection))
                .toList();
        return AdminReservationListResponse.from(reservations, content);
    }

    /** 예약자 정보 조회 — 예약의 예약자 enrich는 reservation 담당(§5-3), 여기서는 응답 변환만. */
    public AdminReservationUserResponse getReserver(Long reservationId) {
        return AdminReservationUserResponse.from(reservationPort.findReserverByAdmin(reservationId));
    }

    private AdminReservationResponse toResponse(
            AdminReservationInfo info,
            MediaProjection mediaProjection
    ) {
        ProjectedImage thumbnail = projectThumbnail(
                info.restaurantImageReference(), mediaProjection);
        return AdminReservationResponse.from(info, thumbnail.url(), thumbnail.image());
    }

    private MediaProjection loadThumbnailProjection(Collection<ImageReference> references) {
        List<MediaImageRequest> requests = references.stream()
                .filter(Objects::nonNull)
                .map(ImageReference::assetId)
                .filter(Objects::nonNull)
                .map(assetId -> new MediaImageRequest(
                        assetId, MediaImageRole.RESTAURANT_THUMBNAIL))
                .distinct()
                .toList();
        if (requests.isEmpty()) {
            return MediaProjection.empty();
        }
        return new MediaProjection(mediaPort.findImages(requests));
    }

    private List<ImageReference> referenceList(ImageReference reference) {
        return reference == null ? List.of() : List.of(reference);
    }

    private ProjectedImage projectThumbnail(
            ImageReference reference,
            MediaProjection mediaProjection
    ) {
        if (reference == null) {
            return ProjectedImage.empty();
        }
        if (reference.assetId() == null) {
            return new ProjectedImage(reference.legacyUrl(), null);
        }
        MediaImage mediaImage = mediaProjection.find(reference);
        String url = mediaImage != null && mediaImage.status() == MediaImageStatus.READY
                ? mediaImage.defaultSource().url()
                : null;
        return new ProjectedImage(url, mediaImage);
    }

    private record MediaProjection(Map<MediaImageRequest, MediaImage> images) {

        private MediaProjection {
            images = Map.copyOf(images);
        }

        private static MediaProjection empty() {
            return new MediaProjection(Map.of());
        }

        private MediaImage find(ImageReference reference) {
            return images.get(new MediaImageRequest(
                    reference.assetId(), MediaImageRole.RESTAURANT_THUMBNAIL));
        }
    }

    private record ProjectedImage(String url, MediaImage image) {

        private static ProjectedImage empty() {
            return new ProjectedImage(null, null);
        }
    }
}
