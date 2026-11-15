package org.sopt.hashi.magazine.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.sopt.hashi.auth.CurrentUserProvider;
import org.sopt.hashi.magazine.AdminMagazineCommand;
import org.sopt.hashi.magazine.AdminMagazineCommand.ImageCommand;
import org.sopt.hashi.magazine.MagazineCardNewsInfo;
import org.sopt.hashi.magazine.MagazineInfo;
import org.sopt.hashi.magazine.code.MagazineErrorCode;
import org.sopt.hashi.magazine.domain.CardNewsReplacement;
import org.sopt.hashi.magazine.domain.CardNewsSource;
import org.sopt.hashi.magazine.domain.Magazine;
import org.sopt.hashi.magazine.domain.MagazineCardNews;
import org.sopt.hashi.magazine.domain.MagazineMeta;
import org.sopt.hashi.magazine.domain.MagazineMetaRepository;
import org.sopt.hashi.magazine.domain.MagazineReactionRepository;
import org.sopt.hashi.magazine.domain.MagazineReactionStatus;
import org.sopt.hashi.magazine.domain.MagazineReactionType;
import org.sopt.hashi.magazine.domain.MagazineRepository;
import org.sopt.hashi.magazine.dto.MagazineBannerListResponse;
import org.sopt.hashi.magazine.dto.MagazineBannerListResponse.MagazineBannerResponse;
import org.sopt.hashi.magazine.dto.MagazineDetailResponse;
import org.sopt.hashi.magazine.dto.MagazineDetailResponse.CardNewsImageResponse;
import org.sopt.hashi.magazine.dto.MagazineDetailResponse.MagazineRestaurantResponse;
import org.sopt.hashi.magazine.dto.MagazineDetailResponse.PriceRangeResponse;
import org.sopt.hashi.magazine.dto.MagazineDetailResponse.TodayBusinessHourResponse;
import org.sopt.hashi.magazine.dto.MagazineListResponse;
import org.sopt.hashi.magazine.dto.MagazineListResponse.MagazineSummaryResponse;
import org.sopt.hashi.media.ImageReference;
import org.sopt.hashi.media.MediaAssetPurpose;
import org.sopt.hashi.media.MediaAssetUse;
import org.sopt.hashi.media.MediaImage;
import org.sopt.hashi.media.MediaImageRequest;
import org.sopt.hashi.media.MediaImageRole;
import org.sopt.hashi.media.MediaImageSelection;
import org.sopt.hashi.media.MediaPort;
import org.sopt.hashi.restaurant.RestaurantDetailInfo;
import org.sopt.hashi.restaurant.RestaurantPort;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.shared.storage.FileStorage;
import org.springframework.dao.DataIntegrityViolationException;
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
    /** 연결 식당 카드에 보여주는 식당 이미지 수(피그마 3칸, 식당 목록과 동일). */
    private static final int RESTAURANT_IMAGE_COUNT = 3;

    private final MagazineRepository magazineRepository;
    private final MagazineMetaRepository magazineMetaRepository;
    private final MagazineReactionRepository magazineReactionRepository;
    private final RestaurantPort restaurantPort;
    private final CurrentUserProvider currentUserProvider;
    private final FileStorage fileStorage;
    private final MediaPort mediaPort;

    public MagazineService(
            MagazineRepository magazineRepository,
            MagazineMetaRepository magazineMetaRepository,
            MagazineReactionRepository magazineReactionRepository,
            RestaurantPort restaurantPort,
            CurrentUserProvider currentUserProvider,
            FileStorage fileStorage,
            MediaPort mediaPort
    ) {
        this.magazineRepository = magazineRepository;
        this.magazineMetaRepository = magazineMetaRepository;
        this.magazineReactionRepository = magazineReactionRepository;
        this.restaurantPort = restaurantPort;
        this.currentUserProvider = currentUserProvider;
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

    /** 매거진 목록 — 최신순 커서 페이지네이션. ⚠️ 필터링·인기순 정렬은 MVP 이후 추가 예정이라 받지 않는다. */
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

    /**
     * 매거진 상세(MAG-002) — 비로그인도 조회할 수 있고, 좋아요 여부만 로그인 회원에 한해 계산한다.
     * 좋아요 수는 meta_magazine 카운터를 읽는다(리액션과 같은 트랜잭션에서 갱신되어 항상 실제 값).
     * 연결 식당은 매핑의 노출 순서대로 RestaurantPort로 enrich하며, 삭제된 식당은 포트가 걸러낸다(§5-2).
     * 카드뉴스 이미지는 asset으로 연결된 것만 모아 MediaPort를 한 번 조회한다.
     */
    public MagazineDetailResponse getDetail(Long magazineId) {
        Magazine magazine = findMagazine(magazineId);
        List<RestaurantDetailInfo> restaurants = restaurantPort.findActiveDetails(magazine.getRestaurantIds());
        MediaProjection cardNewsProjection = loadImages(cardNewsRequests(magazine));

        return new MagazineDetailResponse(
                magazine.getId(),
                magazine.getTitle(),
                toCardNewsImageUrls(magazine, cardNewsProjection),
                toCardNewsInfos(magazine, cardNewsProjection).stream()
                        .map(item -> new CardNewsImageResponse(
                                item.cardNewsId(), item.displayOrder(), item.image(), item.legacyUrl()))
                        .toList(),
                magazine.getContent(),
                List.copyOf(magazine.getHashtags()),
                magazine.getCreatedAt(),
                magazineMetaRepository.findLikeCountOrZero(magazineId),
                isLikedByCurrentUser(magazineId),
                restaurants.stream()
                        .map(this::toRestaurantResponse)
                        .toList());
    }

    /**
     * 어드민 매거진 등록 — legacy key 또는 READY asset을 같은 transaction에서 연결한다.
     * 카운터 행(meta_magazine)을 같은 트랜잭션에서 만들어 이후 원자 UPDATE가 항상 대상을 갖게 한다.
     * 상세 화면 데이터(본문·카드뉴스·해시태그·연결 식당)는 선택이며, 보내지 않으면 비워 둔다.
     */
    @Transactional
    public MagazineInfo create(AdminMagazineCommand command) {
        requireCreateImages(command);
        validateDetailFields(command);
        List<MediaAssetUse> claims = new ArrayList<>();
        ResolvedImage banner = newImage(
                command.bannerImage(), MediaAssetPurpose.MAGAZINE_BANNER, claims);
        ResolvedImage thumbnail = newImage(
                command.thumbnailImage(), MediaAssetPurpose.MAGAZINE_THUMBNAIL, claims);
        Magazine created = Magazine.create(
                command.title(),
                banner.imageKey(), banner.imageAssetId(),
                thumbnail.imageKey(), thumbnail.imageAssetId(),
                command.instagramRedirectUrl(),
                command.content());
        CardNewsReplacement cardNewsReplacement = planCardNews(
                created, command.cardNews(), claims, new ArrayList<>());
        reconcileBindings(claims, List.of());
        // 자식(카드뉴스·연결 식당)은 저장 전에 붙여 매거진과 함께 cascade로 INSERT되게 한다
        applyDetailFields(created, command, cardNewsReplacement);
        Magazine magazine = magazineRepository.save(created);
        magazineMetaRepository.save(MagazineMeta.create(magazine.getId()));
        flushOrRejectDuplicateHashtag();
        // 생성된 id는 응답 body에만 있어 로그로 남겨야 추적 가능하다 (adminId는 MDC)
        log.info("어드민 매거진 등록. magazineId={}", magazine.getId());
        return toInfo(magazine);
    }

    /**
     * 어드민 매거진 수정 — 부분 수정(PATCH), null 필드는 변경하지 않는다.
     * 카드뉴스·해시태그·연결 식당은 보내면 전체 교체하고(빈 목록은 모두 지움), 목록 순서가 노출 순서다.
     */
    @Transactional
    public MagazineInfo update(Long magazineId, AdminMagazineCommand command) {
        if (command == null) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        // 락을 먼저 잡는다 — 검증 조회가 먼저 나가면 그 시점 스냅샷으로 고정돼 락 대기 중 커밋된 다른 수정을 못 본다
        Magazine magazine = findMagazineForUpdate(magazineId);
        validateDetailFields(command);
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
        CardNewsReplacement cardNewsReplacement = planCardNews(
                magazine, command.cardNews(), claims, retires);
        reconcileBindings(claims, retires);
        magazine.update(
                command.title(),
                banner.imageKey(), banner.imageAssetId(),
                thumbnail.imageKey(), thumbnail.imageAssetId(),
                command.instagramRedirectUrl(),
                command.content());
        applyDetailFields(magazine, command, cardNewsReplacement);
        // 새로 추가된 카드뉴스의 id를 응답에 담으려면 INSERT가 먼저 나가야 한다
        flushOrRejectDuplicateHashtag();
        return toInfo(magazine);
    }

    /** 어드민 매거진 삭제. */
    @Transactional
    public void delete(Long magazineId) {
        Magazine magazine = findMagazineForUpdate(magazineId);
        magazine.softDelete();
        // soft delete 동안 asset binding은 유지한다. 물리 정리는 별도 보존 정책 소관이다.
        log.info("어드민 매거진 삭제. magazineId={}", magazineId);
    }

    /**
     * 해시태그 INSERT는 flush 때 나가므로 트랜잭션 안에서 flush해 유니크 제약 위반을 잡는다 — 컬럼 collation이
     * 대소문자 등을 같은 값으로 봐 완전히 같지 않은 해시태그도 DB에서는 중복이다. 500 대신 MAGAZINE-004로 낸다.
     */
    private void flushOrRejectDuplicateHashtag() {
        try {
            magazineRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            String causeMessage = exception.getMostSpecificCause().getMessage();
            if (causeMessage != null && causeMessage.contains("magazine_hashtag.PRIMARY")) {
                throw new BusinessException(MagazineErrorCode.HASHTAG_DUPLICATED, exception);
            }
            throw exception;
        }
    }

    private Magazine findMagazine(Long magazineId) {
        return magazineRepository.findById(magazineId)
                .orElseThrow(() -> new BusinessException(MagazineErrorCode.NOT_FOUND));
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

    // 비로그인·온보딩 토큰은 회원이 아니므로 좋아요 여부를 계산하지 않는다
    private boolean isLikedByCurrentUser(Long magazineId) {
        if (!currentUserProvider.isAuthenticatedUser()) {
            return false;
        }
        return magazineReactionRepository.existsByMagazineIdAndUserIdAndReactionTypeAndStatus(
                magazineId, currentUserProvider.currentUserId(),
                MagazineReactionType.LIKE, MagazineReactionStatus.ACTIVE);
    }

    // 기존 URL 배열에는 표시 가능한 기존 주소와 READY 주소만 담는다(image-delivery-contract §9.3)
    private List<String> toCardNewsImageUrls(Magazine magazine, MediaProjection projection) {
        return magazine.getOrderedCardNews().stream()
                .map(item -> projectCardNews(item, projection).url())
                .filter(Objects::nonNull)
                .toList();
    }

    // wrapper의 legacyUrl은 asset ID가 없는 카드뉴스에만 준다 — asset이 있으면 상태와 관계없이 null(§9.2)
    private List<MagazineCardNewsInfo> toCardNewsInfos(Magazine magazine, MediaProjection projection) {
        return magazine.getOrderedCardNews().stream()
                .map(item -> {
                    ProjectedImage image = projectCardNews(item, projection);
                    return new MagazineCardNewsInfo(
                            item.getId(),
                            item.getDisplayOrder(),
                            image.image(),
                            item.getImageAssetId() == null ? image.url() : null);
                })
                .toList();
    }

    private ProjectedImage projectCardNews(MagazineCardNews item, MediaProjection projection) {
        return projectImage(
                item.getFileKey(),
                item.getImageAssetId(),
                MediaImageRole.MAGAZINE_CARD_NEWS,
                projection);
    }

    private List<MediaImageRequest> cardNewsRequests(Magazine magazine) {
        return magazine.getCardNews().stream()
                .map(item -> imageRequest(
                        item.getImageAssetId(), MediaImageRole.MAGAZINE_CARD_NEWS))
                .filter(Objects::nonNull)
                .toList();
    }

    private MagazineRestaurantResponse toRestaurantResponse(RestaurantDetailInfo restaurant) {
        return new MagazineRestaurantResponse(
                restaurant.id(),
                restaurant.name(),
                restaurant.rating(),
                restaurant.area(),
                restaurant.foodCategory(),
                restaurant.imageUrls().stream()
                        .limit(RESTAURANT_IMAGE_COUNT)
                        .toList(),
                toTodayBusinessHourResponse(restaurant),
                new PriceRangeResponse(
                        restaurant.priceRange().currency(),
                        restaurant.priceRange().minPrice(),
                        restaurant.priceRange().maxPrice()));
    }

    private TodayBusinessHourResponse toTodayBusinessHourResponse(RestaurantDetailInfo restaurant) {
        if (restaurant.todayBusinessHour() == null) {
            return null;
        }
        return new TodayBusinessHourResponse(
                restaurant.todayBusinessHour().date(),
                restaurant.todayBusinessHour().dayOfWeek(),
                restaurant.todayBusinessHour().openTime(),
                restaurant.todayBusinessHour().closeTime(),
                restaurant.todayBusinessHour().closed());
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

    // 배너·썸네일·카드뉴스 asset을 모아 MediaPort를 한 번만 조회한다
    private MagazineInfo toInfo(Magazine magazine) {
        List<MediaImageRequest> requests = Stream.concat(
                        slotRequests(List.of(magazine), true).stream(),
                        cardNewsRequests(magazine).stream())
                .toList();
        MediaProjection projection = loadImages(requests);
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
                magazine.getCreatedAt(),
                magazine.getContent(),
                toCardNewsInfos(magazine, projection),
                List.copyOf(magazine.getHashtags()),
                magazine.getRestaurantIds());
    }

    private void requireCreateImages(AdminMagazineCommand command) {
        if (command == null || command.bannerImage() == null || command.thumbnailImage() == null) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
    }

    // 값의 형식(길이·공백·null·식당 ID 중복)은 요청 DTO가 검증한다. 여기서는 DTO가 확인하지 않는 것만 본다
    private void validateDetailFields(AdminMagazineCommand command) {
        validateCardNewsAssets(command.cardNews());
        validateRestaurantIds(command.restaurantIds());
    }

    // 같은 asset을 카드뉴스 두 장에 쓸 수 없다 — image_asset_id 유니크 제약보다 먼저 거절한다
    private void validateCardNewsAssets(List<ImageCommand> cardNews) {
        if (cardNews == null) {
            return;
        }
        List<UUID> assetIds = cardNews.stream()
                .map(ImageCommand::imageAssetId)
                .filter(Objects::nonNull)
                .toList();
        if (new HashSet<>(assetIds).size() != assetIds.size()) {
            throw new BusinessException(MagazineErrorCode.CARD_NEWS_ASSET_DUPLICATED);
        }
    }

    // 사용자에게 노출되는 식당만 연결할 수 있다 — 삭제된 식당은 상세에서 빠지므로 없는 식당과 같게 거절한다
    private void validateRestaurantIds(List<Long> restaurantIds) {
        if (restaurantIds == null || restaurantIds.isEmpty()) {
            return;
        }
        Set<Long> activeIds = restaurantPort.findActiveDetails(restaurantIds).stream()
                .map(RestaurantDetailInfo::id)
                .collect(Collectors.toSet());
        if (!activeIds.containsAll(restaurantIds)) {
            log.warn("매거진 연결 식당 검증 실패 — 없는 식당 또는 삭제된 식당 포함. requested={}, active={}",
                    restaurantIds, activeIds);
            throw new BusinessException(MagazineErrorCode.RESTAURANT_NOT_FOUND);
        }
    }

    private void applyDetailFields(
            Magazine magazine,
            AdminMagazineCommand command,
            CardNewsReplacement cardNewsReplacement
    ) {
        if (cardNewsReplacement != null) {
            magazine.replaceCardNews(cardNewsReplacement);
        }
        if (command.hashtags() != null) {
            magazine.replaceHashtags(command.hashtags());
        }
        if (command.restaurantIds() != null) {
            magazine.replaceRestaurants(command.restaurantIds());
        }
    }

    /**
     * 카드뉴스를 어떻게 교체할지는 Magazine이 정하고, 여기서는 그 결과를 media claim·retire로 옮기기만 한다.
     * 카드뉴스를 보내지 않은 수정(null)에서는 계획을 세우지 않아 기존 카드뉴스를 읽지 않는다.
     */
    private CardNewsReplacement planCardNews(
            Magazine magazine,
            List<ImageCommand> cardNews,
            List<MediaAssetUse> claims,
            List<MediaAssetUse> retires
    ) {
        if (cardNews == null) {
            return null;
        }
        CardNewsReplacement replacement = magazine.planCardNewsReplacement(cardNews.stream()
                .map(image -> new CardNewsSource(image.imageKey(), image.imageAssetId()))
                .toList());
        replacement.addedAssetIds().forEach(assetId -> claims.add(
                new MediaAssetUse(assetId, MediaAssetPurpose.MAGAZINE_CARD_NEWS)));
        replacement.removedAssetIds().forEach(assetId -> retires.add(
                new MediaAssetUse(assetId, MediaAssetPurpose.MAGAZINE_CARD_NEWS)));
        return replacement;
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
        return loadImages(slotRequests(magazines, includeThumbnail));
    }

    private List<MediaImageRequest> slotRequests(List<Magazine> magazines, boolean includeThumbnail) {
        return magazines.stream()
                .flatMap(magazine -> Stream.of(
                        imageRequest(
                                magazine.getBannerImageAssetId(),
                                MediaImageRole.MAGAZINE_BANNER),
                        includeThumbnail
                                ? imageRequest(
                                        magazine.getThumbnailImageAssetId(),
                                        MediaImageRole.MAGAZINE_THUMBNAIL)
                                : null))
                .filter(Objects::nonNull)
                .toList();
    }

    private MediaProjection loadImages(List<MediaImageRequest> requests) {
        List<MediaImageRequest> distinctRequests = requests.stream().distinct().toList();
        return distinctRequests.isEmpty()
                ? MediaProjection.empty()
                : new MediaProjection(mediaPort.findImages(distinctRequests));
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
        ImageReference reference = assetId == null
                ? ImageReference.legacy(fileStorage.resolveFileUrl(legacyKey))
                : ImageReference.asset(assetId);
        MediaImage image = assetId == null ? null : projection.find(assetId, role);
        MediaImageSelection selection = MediaImageSelection.from(reference, image);
        return new ProjectedImage(selection.url(), selection.image());
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
