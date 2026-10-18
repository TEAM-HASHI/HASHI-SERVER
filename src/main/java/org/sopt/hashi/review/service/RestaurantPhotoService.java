package org.sopt.hashi.review.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.sopt.hashi.media.ImageReference;
import org.sopt.hashi.media.MediaImage;
import org.sopt.hashi.media.MediaImageRequest;
import org.sopt.hashi.media.MediaImageRole;
import org.sopt.hashi.media.MediaImageSelection;
import org.sopt.hashi.media.MediaPort;
import org.sopt.hashi.restaurant.RestaurantPhotoSourceInfo;
import org.sopt.hashi.restaurant.RestaurantPort;
import org.sopt.hashi.review.code.ReviewErrorCode;
import org.sopt.hashi.review.domain.RestaurantPhotoFilter;
import org.sopt.hashi.review.domain.ReviewPhotoRepository;
import org.sopt.hashi.review.dto.RestaurantPhotosResponse;
import org.sopt.hashi.review.dto.RestaurantPhotosResponse.Counts;
import org.sopt.hashi.review.dto.RestaurantPhotosResponse.Photo;
import org.sopt.hashi.shared.error.BusinessException;
import org.sopt.hashi.shared.error.CommonErrorCode;
import org.sopt.hashi.shared.storage.FileStorage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 기존 review→restaurant 포트 방향을 유지하는 사진 탭 읽기 조합. 리뷰는 DB에서 필요한 페이지만 읽는다. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class RestaurantPhotoService {
    private static final int PAGE_SIZE = 20;
    private final RestaurantPort restaurants;
    private final ReviewPhotoRepository reviews;
    private final MediaPort media;
    private final FileStorage storage;

    public RestaurantPhotoService(RestaurantPort restaurants, ReviewPhotoRepository reviews,
                                  MediaPort media, FileStorage storage) {
        this.restaurants = restaurants;
        this.reviews = reviews;
        this.media = media;
        this.storage = storage;
    }

    public RestaurantPhotosResponse getPhotos(long restaurantId, RestaurantPhotoFilter filter, int page) {
        if (restaurantId < 1 || page < 0 || filter == null) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        if (!restaurants.existsActiveById(restaurantId)) {
            throw new BusinessException(ReviewErrorCode.RESTAURANT_NOT_FOUND);
        }
        List<RestaurantPhotoSourceInfo> representative = restaurants.findRepresentativePhotoSources(restaurantId);
        List<RestaurantPhotoSourceInfo> menu = restaurants.findMenuPhotoSources(restaurantId);
        Map<MediaImageRequest, MediaImage> images = loadImages(representative, menu);
        representative = visible(representative, MediaImageRole.PHOTO_DETAIL, images);
        menu = visible(menu, MediaImageRole.PHOTO_DETAIL, images);

        Set<String> seen = new HashSet<>();
        List<RestaurantPhotoSourceInfo> uniqueRepresentative = unique(representative, seen);
        List<String> excludedReviewKeys = representative.stream().map(RestaurantPhotoSourceInfo::fileKey)
                .filter(Objects::nonNull).distinct().toList();
        long reviewCount = reviews.count(restaurantId, false, List.of());
        long uniqueReviewCount = reviews.count(restaurantId, true, excludedReviewKeys);
        List<RestaurantPhotoSourceInfo> uniqueMenu = unique(menu, seen);
        Set<String> reviewMenuKeys = reviews.findExistingKeys(restaurantId,
                uniqueMenu.stream().map(RestaurantPhotoSourceInfo::fileKey).filter(Objects::nonNull).distinct().toList());
        uniqueMenu = uniqueMenu.stream().filter(photo -> !reviewMenuKeys.contains(photo.fileKey())).toList();

        Counts counts = new Counts(uniqueRepresentative.size() + uniqueReviewCount + uniqueMenu.size(),
                representative.size(), menu.size(), reviewCount);
        long totalCount = switch (filter) {
            case ALL -> counts.all();
            case REPRESENTATIVE -> counts.representative();
            case MENU -> counts.menu();
            case REVIEW -> counts.review();
        };
        long offset = (long) page * PAGE_SIZE;
        List<Photo> photos = switch (filter) {
            case REPRESENTATIVE -> sourcePage(representative, offset, RestaurantPhotoFilter.REPRESENTATIVE, images);
            case MENU -> sourcePage(menu, offset, RestaurantPhotoFilter.MENU, images);
            case REVIEW -> reviews.find(restaurantId, false, List.of(), offset, PAGE_SIZE).stream()
                    .map(this::reviewPhoto).toList();
            case ALL -> allPage(restaurantId, offset, totalCount, uniqueRepresentative, uniqueMenu,
                    uniqueReviewCount, excludedReviewKeys, images);
        };
        boolean hasNext = offset + photos.size() < totalCount;
        return new RestaurantPhotosResponse(restaurantId, filter, counts, totalCount, photos, page,
                hasNext ? Math.addExact(page, 1) : null, hasNext);
    }

    private List<Photo> allPage(long restaurantId, long offset, long total,
                              List<RestaurantPhotoSourceInfo> representative, List<RestaurantPhotoSourceInfo> menu,
                              long reviewCount, List<String> excluded, Map<MediaImageRequest, MediaImage> images) {
        List<Slot> slots = new ArrayList<>();
        for (long index = offset; index < total && index < offset + PAGE_SIZE; index++) {
            slots.add(slot(index, representative.size(), reviewCount));
        }
        long firstReview = slots.stream().filter(slot -> slot.type() == RestaurantPhotoFilter.REVIEW)
                .mapToLong(Slot::index).min().orElse(0);
        int reviewSize = (int) slots.stream().filter(slot -> slot.type() == RestaurantPhotoFilter.REVIEW).count();
        List<ReviewPhotoRepository.Photo> reviewPhotos = reviewSize == 0 ? List.of()
                : reviews.find(restaurantId, true, excluded, firstReview, reviewSize);
        List<Photo> result = new ArrayList<>();
        for (Slot slot : slots) {
            result.add(switch (slot.type()) {
                case REPRESENTATIVE -> sourcePhoto(representative.get(Math.toIntExact(slot.index())), slot.type(), images);
                case MENU -> sourcePhoto(menu.get(Math.toIntExact(slot.index())), slot.type(), images);
                case REVIEW -> reviewPhoto(reviewPhotos.get(Math.toIntExact(slot.index() - firstReview)));
                default -> throw new IllegalStateException("ALL is not a photo source");
            });
        }
        return result;
    }

    /** 원본 중복 제거 후 대표 1:리뷰 3, 한 출처 소진 시 나머지, 마지막에 메뉴. 큰 page도 선행 행을 순회하지 않는다. */
    static Slot slot(long index, long representative, long review) {
        long cycles = Math.min(representative, (review + 2) / 3);
        long mixedSize = cycles + Math.min(review, cycles * 3);
        if (index < mixedSize) {
            return index % 4 == 0 ? new Slot(RestaurantPhotoFilter.REPRESENTATIVE, index / 4)
                    : new Slot(RestaurantPhotoFilter.REVIEW, index / 4 * 3 + index % 4 - 1);
        }
        if (index < representative + review) {
            return representative > cycles
                    ? new Slot(RestaurantPhotoFilter.REPRESENTATIVE, cycles + index - mixedSize)
                    : new Slot(RestaurantPhotoFilter.REVIEW, Math.min(review, cycles * 3) + index - mixedSize);
        }
        return new Slot(RestaurantPhotoFilter.MENU, index - representative - review);
    }

    private List<Photo> sourcePage(List<RestaurantPhotoSourceInfo> sources, long offset, RestaurantPhotoFilter type,
                                   Map<MediaImageRequest, MediaImage> images) {
        if (offset >= sources.size()) {
            return List.of();
        }
        return sources.subList((int) offset, (int) Math.min(sources.size(), offset + PAGE_SIZE)).stream()
                .map(source -> sourcePhoto(source, type, images)).toList();
    }

    private Photo sourcePhoto(RestaurantPhotoSourceInfo source, RestaurantPhotoFilter type,
                              Map<MediaImageRequest, MediaImage> images) {
        MediaImageRole role = MediaImageRole.PHOTO_DETAIL;
        String legacyUrl = source.assetId() == null ? storage.resolveFileUrl(source.fileKey()) : null;
        MediaImage image = source.assetId() == null ? null : images.get(new MediaImageRequest(source.assetId(), role));
        MediaImageSelection selected = MediaImageSelection.from(new ImageReference(source.assetId(), legacyUrl), image);
        return new Photo(type.name() + ":" + source.id(), type, selected.url(), selected.image());
    }

    private Photo reviewPhoto(ReviewPhotoRepository.Photo photo) {
        return new Photo("REVIEW:" + photo.id(), RestaurantPhotoFilter.REVIEW, storage.resolveFileUrl(photo.fileKey()), null);
    }

    private Map<MediaImageRequest, MediaImage> loadImages(List<RestaurantPhotoSourceInfo> representative,
                                                        List<RestaurantPhotoSourceInfo> menu) {
        List<MediaImageRequest> requests = new ArrayList<>();
        representative.stream().filter(source -> source.assetId() != null)
                .forEach(source -> requests.add(new MediaImageRequest(source.assetId(), MediaImageRole.PHOTO_DETAIL)));
        menu.stream().filter(source -> source.assetId() != null)
                .forEach(source -> requests.add(new MediaImageRequest(source.assetId(), MediaImageRole.PHOTO_DETAIL)));
        List<MediaImageRequest> distinctRequests = requests.stream().distinct().toList();
        Map<MediaImageRequest, MediaImage> result = new HashMap<>();
        for (int start = 0; start < distinctRequests.size(); start += 500) {
            result.putAll(media.findImages(distinctRequests.subList(start, Math.min(start + 500, distinctRequests.size()))));
        }
        return result;
    }

    private static List<RestaurantPhotoSourceInfo> visible(List<RestaurantPhotoSourceInfo> sources, MediaImageRole role,
                                                       Map<MediaImageRequest, MediaImage> images) {
        return sources.stream().filter(source -> source.assetId() == null
                || images.containsKey(new MediaImageRequest(source.assetId(), role))).toList();
    }

    private static List<RestaurantPhotoSourceInfo> unique(Collection<RestaurantPhotoSourceInfo> sources, Set<String> seen) {
        List<RestaurantPhotoSourceInfo> result = new ArrayList<>();
        for (RestaurantPhotoSourceInfo source : sources) {
            String key = source.fileKey() == null ? null : "key:" + source.fileKey();
            String asset = source.assetId() == null ? null : "asset:" + source.assetId();
            boolean duplicate = (key != null && seen.contains(key)) || (asset != null && seen.contains(asset));
            if (key != null) seen.add(key);
            if (asset != null) seen.add(asset);
            if (!duplicate) result.add(source);
        }
        return result;
    }

    record Slot(RestaurantPhotoFilter type, long index) { }
}
