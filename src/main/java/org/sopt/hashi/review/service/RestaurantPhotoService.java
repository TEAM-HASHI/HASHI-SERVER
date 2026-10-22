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

    public RestaurantPhotosResponse getPhotos(long restaurantId, RestaurantPhotoFilter filter, String cursor) {
        if (restaurantId < 1 || filter == null) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        var position = RestaurantPhotoCursorCodec.decode(cursor, restaurantId, filter);
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
                uniqueMenu.stream().map(RestaurantPhotoSourceInfo::fileKey)
                        .filter(Objects::nonNull).distinct().toList());
        uniqueMenu = uniqueMenu.stream().filter(photo -> !reviewMenuKeys.contains(photo.fileKey())).toList();

        Counts counts = new Counts(uniqueRepresentative.size() + uniqueReviewCount + uniqueMenu.size(),
                representative.size(), menu.size(), reviewCount);
        long totalCount = switch (filter) {
            case ALL -> counts.all();
            case REPRESENTATIVE -> counts.representative();
            case MENU -> counts.menu();
            case REVIEW -> counts.review();
        };
        List<RestaurantPhotoSourceInfo> selectedRepresentative = filter == RestaurantPhotoFilter.ALL
                ? uniqueRepresentative : representative;
        List<RestaurantPhotoSourceInfo> selectedMenu = filter == RestaurantPhotoFilter.ALL ? uniqueMenu : menu;
        selectedRepresentative = selectedRepresentative.stream().filter(source ->
                source.displayOrder() > position.representativeOrder()
                        || (source.displayOrder() == position.representativeOrder()
                        && source.id() > position.representativeId())).toList();
        selectedMenu = selectedMenu.stream().filter(source -> source.id() > position.menuId()).toList();
        boolean includeReviews = filter == RestaurantPhotoFilter.REVIEW
                || (filter == RestaurantPhotoFilter.ALL && !position.menuStarted());
        List<ReviewPhotoRepository.Photo> reviewPhotos = includeReviews
                ? reviews.find(restaurantId, filter == RestaurantPhotoFilter.ALL,
                        filter == RestaurantPhotoFilter.ALL ? excludedReviewKeys : List.of(),
                        position.review(), PAGE_SIZE + 1)
                : List.of();
        List<MediaImageRequest> reviewRequests = reviewPhotos.stream()
                .map(ReviewPhotoRepository.Photo::imageAssetId).filter(Objects::nonNull)
                .map(assetId -> new MediaImageRequest(assetId, MediaImageRole.REVIEW_DETAIL)).distinct().toList();
        Map<MediaImageRequest, MediaImage> reviewImages = reviewRequests.isEmpty()
                ? Map.of() : media.findImages(reviewRequests);
        List<Photo> result = new ArrayList<>();
        var current = position;
        var next = position;
        int representativeIndex = 0;
        int reviewIndex = 0;
        int menuIndex = 0;
        while (result.size() <= PAGE_SIZE) {
            boolean hasRepresentative = representativeIndex < selectedRepresentative.size();
            boolean hasReview = reviewIndex < reviewPhotos.size();
            RestaurantPhotoFilter type = filter;
            if (filter == RestaurantPhotoFilter.ALL) {
                type = nextSource(current.phase(), current.menuStarted(), hasRepresentative, hasReview);
            }
            if (type == RestaurantPhotoFilter.REPRESENTATIVE && hasRepresentative) {
                var source = selectedRepresentative.get(representativeIndex++);
                result.add(sourcePhoto(source, type, images));
                current = new RestaurantPhotoCursorCodec.Cursor(source.displayOrder(), source.id(), current.menuId(),
                        current.review(), 1, false);
            } else if (type == RestaurantPhotoFilter.REVIEW && hasReview) {
                var photo = reviewPhotos.get(reviewIndex++);
                result.add(reviewPhoto(photo, reviewImages));
                current = new RestaurantPhotoCursorCodec.Cursor(current.representativeOrder(),
                        current.representativeId(), current.menuId(), photo.position(),
                        (current.phase() + 1) % 4, false);
            } else if (type == RestaurantPhotoFilter.MENU && menuIndex < selectedMenu.size()) {
                var source = selectedMenu.get(menuIndex++);
                result.add(sourcePhoto(source, type, images));
                current = new RestaurantPhotoCursorCodec.Cursor(current.representativeOrder(),
                        current.representativeId(), source.id(), current.review(), current.phase(), true);
            } else {
                break;
            }
            if (result.size() == PAGE_SIZE) {
                next = current;
            }
        }
        boolean hasNext = result.size() > PAGE_SIZE;
        List<Photo> page = hasNext ? List.copyOf(result.subList(0, PAGE_SIZE)) : List.copyOf(result);
        return new RestaurantPhotosResponse(restaurantId, filter, counts, totalCount, page,
                hasNext ? RestaurantPhotoCursorCodec.encode(next, restaurantId, filter) : null, hasNext);
    }

    static RestaurantPhotoFilter nextSource(int phase, boolean menuStarted, boolean representative, boolean review) {
        if (menuStarted || (!representative && !review)) {
            return RestaurantPhotoFilter.MENU;
        }
        if (representative && (phase == 0 || !review)) {
            return RestaurantPhotoFilter.REPRESENTATIVE;
        }
        return RestaurantPhotoFilter.REVIEW;
    }

    private Photo sourcePhoto(RestaurantPhotoSourceInfo source, RestaurantPhotoFilter type,
                              Map<MediaImageRequest, MediaImage> images) {
        MediaImageRole role = MediaImageRole.PHOTO_DETAIL;
        String legacyUrl = source.assetId() == null ? storage.resolveFileUrl(source.fileKey()) : null;
        MediaImage image = source.assetId() == null
                ? null : images.get(new MediaImageRequest(source.assetId(), role));
        MediaImageSelection selected = MediaImageSelection.from(new ImageReference(source.assetId(), legacyUrl), image);
        return new Photo(type.name() + ":" + source.id(), type, selected.url(), selected.image());
    }

    private Photo reviewPhoto(ReviewPhotoRepository.Photo photo, Map<MediaImageRequest, MediaImage> images) {
        String legacyUrl = photo.imageAssetId() == null ? storage.resolveFileUrl(photo.fileKey()) : null;
        MediaImage rendition = photo.imageAssetId() == null ? null
                : images.get(new MediaImageRequest(photo.imageAssetId(), MediaImageRole.REVIEW_DETAIL));
        MediaImageSelection selected = MediaImageSelection.from(
                new ImageReference(photo.imageAssetId(), legacyUrl), rendition);
        return new Photo("REVIEW:" + photo.id(), RestaurantPhotoFilter.REVIEW,
                selected.url(), selected.image());
    }

    private Map<MediaImageRequest, MediaImage> loadImages(List<RestaurantPhotoSourceInfo> representative,
                                                        List<RestaurantPhotoSourceInfo> menu) {
        List<MediaImageRequest> requests = new ArrayList<>();
        representative.stream().filter(source -> source.assetId() != null)
                .forEach(source -> requests.add(
                        new MediaImageRequest(source.assetId(), MediaImageRole.PHOTO_DETAIL)));
        menu.stream().filter(source -> source.assetId() != null)
                .forEach(source -> requests.add(
                        new MediaImageRequest(source.assetId(), MediaImageRole.PHOTO_DETAIL)));
        List<MediaImageRequest> distinctRequests = requests.stream().distinct().toList();
        Map<MediaImageRequest, MediaImage> result = new HashMap<>();
        for (int start = 0; start < distinctRequests.size(); start += 500) {
            result.putAll(media.findImages(
                    distinctRequests.subList(start, Math.min(start + 500, distinctRequests.size()))));
        }
        return result;
    }

    private static List<RestaurantPhotoSourceInfo> visible(List<RestaurantPhotoSourceInfo> sources, MediaImageRole role,
                                                       Map<MediaImageRequest, MediaImage> images) {
        return sources.stream().filter(source -> source.assetId() == null
                || images.containsKey(new MediaImageRequest(source.assetId(), role))).toList();
    }

    private static List<RestaurantPhotoSourceInfo> unique(
            Collection<RestaurantPhotoSourceInfo> sources, Set<String> seen) {
        List<RestaurantPhotoSourceInfo> result = new ArrayList<>();
        for (RestaurantPhotoSourceInfo source : sources) {
            String key = source.fileKey() == null ? null : "key:" + source.fileKey();
            String asset = source.assetId() == null ? null : "asset:" + source.assetId();
            boolean duplicate = (key != null && seen.contains(key)) || (asset != null && seen.contains(asset));
            if (key != null) {
                seen.add(key);
            }
            if (asset != null) {
                seen.add(asset);
            }
            if (!duplicate) {
                result.add(source);
            }
        }
        return result;
    }

}
