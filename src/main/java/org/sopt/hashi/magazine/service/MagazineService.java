package org.sopt.hashi.magazine.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.sopt.hashi.magazine.AdminMagazineCommand;
import org.sopt.hashi.magazine.AdminMagazineCommand.ImageCommand;
import org.sopt.hashi.magazine.MagazineInfo;
import org.sopt.hashi.magazine.code.MagazineErrorCode;
import org.sopt.hashi.magazine.domain.Magazine;
import org.sopt.hashi.magazine.domain.MagazineRepository;
import org.sopt.hashi.magazine.dto.MagazineBannerListResponse;
import org.sopt.hashi.magazine.dto.MagazineBannerListResponse.MagazineBannerResponse;
import org.sopt.hashi.magazine.dto.MagazineListResponse;
import org.sopt.hashi.magazine.dto.MagazineListResponse.MagazineSummaryResponse;
import org.sopt.hashi.media.MediaAssetPurpose;
import org.sopt.hashi.media.MediaAssetUse;
import org.sopt.hashi.media.MediaImage;
import org.sopt.hashi.media.MediaImageRequest;
import org.sopt.hashi.media.MediaImageRole;
import org.sopt.hashi.media.MediaImageStatus;
import org.sopt.hashi.media.MediaPort;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.shared.storage.FileStorage;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional(readOnly = true)
public class MagazineService {

    private static final int BANNER_COUNT = 5;
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 50;

    private final MagazineRepository magazineRepository;
    private final FileStorage fileStorage;
    private final MediaPort mediaPort;

    public MagazineService(
            MagazineRepository magazineRepository,
            FileStorage fileStorage,
            MediaPort mediaPort
    ) {
        this.magazineRepository = magazineRepository;
        this.fileStorage = fileStorage;
        this.mediaPort = mediaPort;
    }

    /** 매거진 배너 목록 — 최신 매거진 5개의 배너를 인스타그램 리다이렉트 URL과 함께 내린다. */
    public MagazineBannerListResponse getBanners() {
        List<Magazine> magazines = magazineRepository.findAllByOrderByIdDesc(
                PageRequest.of(0, BANNER_COUNT));
        MediaProjection projection = loadProjection(magazines, false);
        return new MagazineBannerListResponse(magazines.stream()
                .map(magazine -> toBannerResponse(magazine, projection))
                .toList());
    }

    /** 매거진 목록 — 최신순 커서 페이지네이션. ⚠️ 필터링은 MVP 이후 추가 예정이라 받지 않는다. */
    public MagazineListResponse getMagazines(Long cursor, Integer size) {
        int pageSize = normalizeSize(size);
        List<Magazine> rows = fetchPage(cursor, PageRequest.of(0, pageSize + 1));

        boolean hasNext = rows.size() > pageSize;
        List<Magazine> pageContent = hasNext ? rows.subList(0, pageSize) : rows;
        Long nextCursor = hasNext ? pageContent.getLast().getId() : null;
        MediaProjection projection = loadProjection(pageContent);

        return new MagazineListResponse(
                pageContent.stream()
                        .map(magazine -> toSummaryResponse(magazine, projection))
                        .toList(),
                nextCursor,
                hasNext);
    }

    /** 어드민 매거진 등록 — legacy key 또는 READY asset을 같은 transaction에서 연결한다. */
    @Transactional
    public MagazineInfo create(AdminMagazineCommand command) {
        requireCreateImages(command);
        List<MediaAssetUse> claims = new ArrayList<>();
        ResolvedImage banner = newImage(
                command.bannerImage(), MediaAssetPurpose.MAGAZINE_BANNER, claims);
        ResolvedImage thumbnail = newImage(
                command.thumbnailImage(), MediaAssetPurpose.MAGAZINE_THUMBNAIL, claims);
        reconcileBindings(claims, List.of());
        Magazine magazine = magazineRepository.save(Magazine.create(
                command.title(),
                banner.imageKey(), banner.imageAssetId(),
                thumbnail.imageKey(), thumbnail.imageAssetId(),
                command.instagramRedirectUrl()));
        // 생성된 id는 응답 body에만 있어 로그로 남겨야 추적 가능하다 (adminId는 MDC)
        log.info("어드민 매거진 등록. magazineId={}", magazine.getId());
        return toInfo(magazine, loadProjection(List.of(magazine)));
    }

    /** 어드민 매거진 수정 — 부분 수정(PATCH), null 필드는 변경하지 않는다. */
    @Transactional
    public MagazineInfo update(Long magazineId, AdminMagazineCommand command) {
        if (command == null) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        Magazine magazine = findMagazineForUpdate(magazineId);
        List<MediaAssetUse> claims = new ArrayList<>();
        List<MediaAssetUse> retires = new ArrayList<>();
        ResolvedImage banner = resolveImage(
                magazine.getBannerKey(),
                magazine.getBannerImageAssetId(),
                command.bannerImage(),
                MediaAssetPurpose.MAGAZINE_BANNER,
                claims,
                retires);
        ResolvedImage thumbnail = resolveImage(
                magazine.getThumbnailKey(),
                magazine.getThumbnailImageAssetId(),
                command.thumbnailImage(),
                MediaAssetPurpose.MAGAZINE_THUMBNAIL,
                claims,
                retires);
        reconcileBindings(claims, retires);
        magazine.update(
                command.title(),
                banner.imageKey(), banner.imageAssetId(),
                thumbnail.imageKey(), thumbnail.imageAssetId(),
                command.instagramRedirectUrl());
        return toInfo(magazine, loadProjection(List.of(magazine)));
    }

    /** 어드민 매거진 삭제. */
    @Transactional
    public void delete(Long magazineId) {
        Magazine magazine = findMagazineForUpdate(magazineId);
        magazineRepository.delete(magazine);
        // soft delete 동안 asset binding은 유지한다. 물리 정리는 별도 보존 정책 소관이다.
        log.info("어드민 매거진 삭제. magazineId={}", magazineId);
    }

    private Magazine findMagazineForUpdate(Long magazineId) {
        return magazineRepository.findByIdForUpdate(magazineId)
                .orElseThrow(() -> new BusinessException(MagazineErrorCode.NOT_FOUND));
    }

    private List<Magazine> fetchPage(Long cursor, Pageable pageable) {
        return (cursor == null)
                ? magazineRepository.findAllByOrderByIdDesc(pageable)
                : magazineRepository.findByIdLessThanOrderByIdDesc(cursor, pageable);
    }

    // 페이지 사이즈 검증
    private int normalizeSize(Integer size) {
        if (size == null || size < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    // 매거진 큐레이선 응답
    private MagazineBannerResponse toBannerResponse(
            Magazine magazine,
            MediaProjection projection
    ) {
        ProjectedImage banner = projectImage(
                magazine.getBannerKey(),
                magazine.getBannerImageAssetId(),
                MediaImageRole.MAGAZINE_BANNER,
                projection);
        return new MagazineBannerResponse(
                magazine.getId(),
                magazine.getTitle(),
                banner.url(),
                banner.image(),
                magazine.getInstagramRedirectUrl());
    }

    // 매거진 리스트 응답
    private MagazineSummaryResponse toSummaryResponse(
            Magazine magazine,
            MediaProjection projection
    ) {
        ProjectedImage banner = projectImage(
                magazine.getBannerKey(),
                magazine.getBannerImageAssetId(),
                MediaImageRole.MAGAZINE_BANNER,
                projection);
        ProjectedImage thumbnail = projectImage(
                magazine.getThumbnailKey(),
                magazine.getThumbnailImageAssetId(),
                MediaImageRole.MAGAZINE_THUMBNAIL,
                projection);
        return new MagazineSummaryResponse(
                magazine.getId(),
                magazine.getTitle(),
                banner.url(),
                banner.image(),
                thumbnail.url(),
                thumbnail.image(),
                magazine.getInstagramRedirectUrl(),
                magazine.getCreatedAt());
    }

    private MagazineInfo toInfo(Magazine magazine, MediaProjection projection) {
        ProjectedImage banner = projectImage(
                magazine.getBannerKey(),
                magazine.getBannerImageAssetId(),
                MediaImageRole.MAGAZINE_BANNER,
                projection);
        ProjectedImage thumbnail = projectImage(
                magazine.getThumbnailKey(),
                magazine.getThumbnailImageAssetId(),
                MediaImageRole.MAGAZINE_THUMBNAIL,
                projection);
        return new MagazineInfo(
                magazine.getId(),
                magazine.getTitle(),
                banner.url(),
                banner.image(),
                thumbnail.url(),
                thumbnail.image(),
                magazine.getInstagramRedirectUrl(),
                magazine.getCreatedAt());
    }

    private void requireCreateImages(AdminMagazineCommand command) {
        if (command == null || command.bannerImage() == null || command.thumbnailImage() == null) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
    }

    private ResolvedImage newImage(
            ImageCommand command,
            MediaAssetPurpose purpose,
            List<MediaAssetUse> claims
    ) {
        if (command.imageAssetId() != null) {
            claims.add(new MediaAssetUse(command.imageAssetId(), purpose));
        }
        return new ResolvedImage(command.imageKey(), command.imageAssetId());
    }

    private ResolvedImage resolveImage(
            String currentKey,
            UUID currentAssetId,
            ImageCommand command,
            MediaAssetPurpose purpose,
            List<MediaAssetUse> claims,
            List<MediaAssetUse> retires
    ) {
        if (command == null) {
            return new ResolvedImage(currentKey, currentAssetId);
        }
        if (command.imageAssetId() != null) {
            if (Objects.equals(command.imageAssetId(), currentAssetId)) {
                return new ResolvedImage(currentKey, currentAssetId);
            }
            addRetire(currentAssetId, purpose, retires);
            claims.add(new MediaAssetUse(command.imageAssetId(), purpose));
            return new ResolvedImage(null, command.imageAssetId());
        }
        if (Objects.equals(command.imageKey(), currentKey)) {
            return new ResolvedImage(currentKey, currentAssetId);
        }
        addRetire(currentAssetId, purpose, retires);
        return new ResolvedImage(command.imageKey(), null);
    }

    private void addRetire(
            UUID assetId,
            MediaAssetPurpose purpose,
            List<MediaAssetUse> retires
    ) {
        if (assetId != null) {
            retires.add(new MediaAssetUse(assetId, purpose));
        }
    }

    private void reconcileBindings(
            List<MediaAssetUse> claims,
            List<MediaAssetUse> retires
    ) {
        if (!claims.isEmpty() || !retires.isEmpty()) {
            mediaPort.reconcileBindings(claims, retires);
        }
    }

    private MediaProjection loadProjection(List<Magazine> magazines) {
        return loadProjection(magazines, true);
    }

    private MediaProjection loadProjection(List<Magazine> magazines, boolean includeThumbnail) {
        List<MediaImageRequest> requests = magazines.stream()
                .flatMap(magazine -> java.util.stream.Stream.of(
                        imageRequest(
                                magazine.getBannerImageAssetId(),
                                MediaImageRole.MAGAZINE_BANNER),
                        includeThumbnail
                                ? imageRequest(
                                        magazine.getThumbnailImageAssetId(),
                                        MediaImageRole.MAGAZINE_THUMBNAIL)
                                : null))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        return requests.isEmpty()
                ? MediaProjection.empty()
                : new MediaProjection(mediaPort.findImages(requests));
    }

    private MediaImageRequest imageRequest(UUID assetId, MediaImageRole role) {
        return assetId == null ? null : new MediaImageRequest(assetId, role);
    }

    private ProjectedImage projectImage(
            String legacyKey,
            UUID assetId,
            MediaImageRole role,
            MediaProjection projection
    ) {
        if (assetId == null) {
            return new ProjectedImage(fileStorage.resolveFileUrl(legacyKey), null);
        }
        MediaImage image = projection.find(assetId, role);
        String url = image != null && image.status() == MediaImageStatus.READY
                ? image.defaultSource().url()
                : null;
        return new ProjectedImage(url, image);
    }

    private record ResolvedImage(String imageKey, UUID imageAssetId) {
    }

    private record MediaProjection(Map<MediaImageRequest, MediaImage> images) {

        private MediaProjection {
            images = Map.copyOf(images);
        }

        private static MediaProjection empty() {
            return new MediaProjection(Map.of());
        }

        private MediaImage find(UUID assetId, MediaImageRole role) {
            return images.get(new MediaImageRequest(assetId, role));
        }
    }

    private record ProjectedImage(String url, MediaImage image) {
    }
}
