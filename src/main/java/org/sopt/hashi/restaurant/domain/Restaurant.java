package org.sopt.hashi.restaurant.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.SQLDelete;
import org.sopt.hashi.BaseTimeEntity;

/**
 * 삭제는 soft delete(deleted=true). 어드민 목록·예약·리뷰 화면이 삭제된 식당을 계속 조회해야 하므로
 * 전역 필터(@SQLRestriction) 없이 사용자 노출 쿼리에만 deleted 조건을 명시한다.
 */
@Getter
@Entity
@Table(name = "restaurant")
@SQLDelete(sql = "UPDATE restaurant SET deleted = true WHERE id = ?")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Restaurant extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    @Column(name = "local_name", length = 100, nullable = false)
    private String localName;

    @Column(name = "summary", length = 100, nullable = false)
    private String summary;

    @Column(name = "description", length = 500, nullable = false)
    private String description;

    @Column(name = "address", length = 255, nullable = false)
    private String address;

    @Column(name = "area", length = 20, nullable = false)
    private String area;

    /** 운영 관광 지역은 별도 Aggregate의 ID로만 참조한다. null은 미분류다. */
    @Column(name = "map_region_id")
    private Long mapRegionId;

    /** 소유 측 FK로 위치 없음도 추가 SELECT 없이 판별한다. soft delete 시 자식은 보존한다. */
    @OneToOne(fetch = FetchType.LAZY, cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @JoinColumn(name = "location_id", unique = true)
    private RestaurantLocation location;

    @Enumerated(EnumType.STRING)
    @Column(name = "genre", length = 20, nullable = false)
    private RestaurantGenre genre;

    /** 카드 표시용 음식 카테고리(자유 텍스트, #145). 장르 필터 축은 genre가, 음식점 분류 축은 placeType이 전담한다. */
    @Column(name = "food_category", length = 20, nullable = false)
    private String foodCategory;

    /** 음식점 분류(음식점·카페·주점, #211) — 저장 컬렉션 분류 필터 축. genre·foodCategory와 별개의 상위 구분이다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "place_type", length = 20, nullable = false)
    private RestaurantPlaceType placeType;

    @Enumerated(EnumType.STRING)
    @Column(name = "price_currency", length = 3, nullable = false)
    private PriceCurrency priceCurrency;

    @Column(name = "price_min", precision = 15, scale = 2, nullable = false)
    private BigDecimal minPrice;

    @Column(name = "price_max", precision = 15, scale = 2, nullable = false)
    private BigDecimal maxPrice;

    @Column(name = "rating_sum", nullable = false, updatable = false)
    private long ratingSum;

    @Column(name = "review_count", nullable = false, updatable = false)
    private long reviewCount;

    @Column(name = "rating", precision = 2, scale = 1, nullable = false, updatable = false)
    private BigDecimal rating;

    @Column(name = "deleted", nullable = false)
    private boolean deleted;

    @BatchSize(size = 100)
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "restaurant_hashtag", joinColumns = @JoinColumn(name = "restaurant_id"))
    @Column(name = "hashtag", length = 20, nullable = false)
    private Set<String> hashtags = new LinkedHashSet<>();

    @BatchSize(size = 100)
    @Enumerated(EnumType.STRING)
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "restaurant_curation_type", joinColumns = @JoinColumn(name = "restaurant_id"))
    @Column(name = "curation_type", length = 30, nullable = false)
    private Set<RestaurantCurationType> curationTypes = new LinkedHashSet<>();

    @BatchSize(size = 100)
    @OneToMany(mappedBy = "restaurant", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<RestaurantMenu> menus = new ArrayList<>();

    @BatchSize(size = 100)
    @OrderBy("displayOrder ASC")
    @OneToMany(mappedBy = "restaurant", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<RestaurantImage> images = new ArrayList<>();

    @BatchSize(size = 100)
    @OneToMany(mappedBy = "restaurant", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<RestaurantBusinessHour> businessHours = new ArrayList<>();

    private Restaurant(String name, String localName, String summary, String description, String address,
                       String area, RestaurantGenre genre, String foodCategory, RestaurantPlaceType placeType,
                       PriceCurrency priceCurrency, BigDecimal minPrice, BigDecimal maxPrice) {
        this.name = name;
        this.localName = localName;
        this.summary = summary;
        this.description = description;
        this.address = address;
        this.area = area;
        this.genre = genre;
        this.foodCategory = foodCategory;
        this.placeType = placeType;
        this.priceCurrency = priceCurrency;
        this.minPrice = minPrice;
        this.maxPrice = maxPrice;
        this.ratingSum = 0;
        this.reviewCount = 0;
        this.rating = BigDecimal.ZERO.setScale(1);
        this.deleted = false;
    }

    public static Restaurant create(String name, String localName, String summary, String description,
                                    String address, String area, RestaurantGenre genre,
                                    String foodCategory, RestaurantPlaceType placeType,
                                    PriceCurrency priceCurrency, BigDecimal minPrice,
                                    BigDecimal maxPrice) {
        return new Restaurant(name, localName, summary, description, address, area, genre, foodCategory,
                placeType, priceCurrency, minPrice, maxPrice);
    }

    /** 부분 수정(PATCH) — null 필드는 기존 값을 유지한다(값 비우기 불가, magazine과 동일 정책). */
    public void updateBasicInfo(String name, String localName, String summary, String description,
                                String address, String area, RestaurantGenre genre,
                                String foodCategory, RestaurantPlaceType placeType,
                                PriceCurrency priceCurrency, BigDecimal minPrice, BigDecimal maxPrice) {
        if (name != null) {
            this.name = name;
        }
        if (localName != null) {
            this.localName = localName;
        }
        if (summary != null) {
            this.summary = summary;
        }
        if (description != null) {
            this.description = description;
        }
        boolean addressChanged = address != null && !Objects.equals(this.address, address);
        if (addressChanged) {
            if (location != null) {
                location.addressChanged();
            }
            this.address = address;
        }
        if (area != null) {
            this.area = area;
        }
        if (genre != null) {
            this.genre = genre;
        }
        if (foodCategory != null) {
            this.foodCategory = foodCategory;
        }
        if (placeType != null) {
            this.placeType = placeType;
        }
        if (priceCurrency != null) {
            this.priceCurrency = priceCurrency;
        }
        if (minPrice != null) {
            this.minPrice = minPrice;
        }
        if (maxPrice != null) {
            this.maxPrice = maxPrice;
        }
    }

    /** 어드민 삭제(soft delete) — 사용자 노출만 차단하고 예약·리뷰가 참조하는 데이터는 보존한다. */
    public void softDelete() {
        this.deleted = true;
    }

    /** 실제 지역 존재·활성 검증은 후속 운영 Service에서 수행한다. null로 미분류로 되돌릴 수 있다. */
    public void assignMapRegion(Long mapRegionId) {
        if (mapRegionId != null && mapRegionId <= 0) {
            throw new IllegalArgumentException("관광 지역 ID는 양수여야 합니다");
        }
        this.mapRegionId = mapRegionId;
    }

    /** 후속 Service가 식당 잠금과 같은 transaction에서 호출하고 durable 작업을 연결한다. */
    public void requestLocationResolution() {
        requireActiveForLocation();
        if (location == null) {
            location = RestaurantLocation.pending();
        } else {
            location.requestRetry();
        }
    }

    public void refreshLocation() {
        requireActiveForLocation();
        if (location == null) {
            throw new IllegalStateException("갱신할 위치가 없습니다");
        }
        location.beginRefresh();
    }

    /** Retention also applies to soft-deleted restaurants; original restaurant data stays intact. */
    public boolean purgeGoogleLocation(long revision, UUID request, LocalDateTime obtained, LocalDateTime until,
                                       LocalDateTime purgeBefore) {
        return location != null && location.purgeGoogle(revision, request, obtained, until, purgeBefore);
    }

    public boolean retryLocationWhenDue(Clock clock) {
        return !deleted && location != null && location.beginScheduledRetry(clock);
    }

    /** 후속 worker는 식당 잠금 후 revision/requestId뿐 아니라 job lease도 검사해야 한다. */
    public boolean completeLocation(long expectedRevision, UUID expectedRequestId, MapCoordinates coordinates,
                                    RestaurantLocationSource source, LocalDateTime obtainedAt,
                                    LocalDateTime validUntil, Clock clock) {
        return !deleted && location != null && location.complete(expectedRevision, expectedRequestId,
                coordinates, source, obtainedAt, validUntil, clock);
    }

    public boolean deferLocation(long expectedRevision, UUID expectedRequestId,
                                 LocalDateTime nextAttemptAt, Clock clock) {
        return !deleted && location != null
                && location.defer(expectedRevision, expectedRequestId, nextAttemptAt, clock);
    }

    public boolean rejectLocation(long expectedRevision, UUID expectedRequestId, RestaurantLocationStatus outcome) {
        return !deleted && location != null && location.reject(expectedRevision, expectedRequestId, outcome);
    }

    /** 관광 지역 미분류 여부는 일반 지도 노출 조건에 포함하지 않는다. */
    public boolean hasUsableMapLocation(Clock clock) {
        return !deleted && location != null && location.isUsable(clock);
    }

    public void replaceHashtags(List<String> hashtags) {
        this.hashtags.clear();
        if (hashtags != null) {
            this.hashtags.addAll(hashtags);
        }
    }

    /** 정렬 순서가 가장 빠른 식당 이미지를 대표 이미지로 사용한다. */
    public String getThumbnailFileKey() {
        return getThumbnailImage()
                .map(RestaurantImage::getFileKey)
                .orElse(null);
    }

    /** 대표 association 자체를 반환해 assetId와 legacy key를 함께 보존한다. */
    public Optional<RestaurantImage> getThumbnailImage() {
        return images.stream()
                .min(Comparator.comparingInt(RestaurantImage::getDisplayOrder));
    }

    public void replaceCurationTypes(List<RestaurantCurationType> curationTypes) {
        this.curationTypes.clear();
        if (curationTypes != null) {
            this.curationTypes.addAll(curationTypes);
        }
    }

    public void replaceMenus(List<RestaurantMenu> menus) {
        this.menus.clear();
        if (menus != null) {
            menus.forEach(this::addMenu);
        }
    }

    public void addMenu(RestaurantMenu menu) {
        menu.assignRestaurant(this);
        this.menus.add(menu);
    }

    public void removeMenusNotIn(Set<Long> retainedMenuIds) {
        this.menus.removeIf(menu -> menu.getId() == null || !retainedMenuIds.contains(menu.getId()));
    }

    public void replaceImages(List<RestaurantImage> images) {
        this.images.clear();
        if (images != null) {
            images.forEach(this::addImage);
        }
    }

    public void addImage(RestaurantImage image) {
        image.assignRestaurant(this);
        this.images.add(image);
    }

    public void removeImagesNotIn(Set<Long> retainedImageIds) {
        this.images.removeIf(image -> image.getId() != null
                && !retainedImageIds.contains(image.getId()));
    }

    public void moveImagesToTemporaryOrders(int firstTemporaryOrder) {
        int nextOrder = firstTemporaryOrder;
        for (RestaurantImage image : images) {
            image.setDisplayOrder(nextOrder);
            nextOrder = Math.addExact(nextOrder, 1);
        }
    }

    public void sortImagesByDisplayOrder() {
        this.images.sort(Comparator.comparingInt(RestaurantImage::getDisplayOrder));
    }

    /** legacy 이미지 association이 그대로일 때만 backfill asset을 연결한다. */
    public boolean attachBackfilledImage(long imageId, String expectedFileKey, UUID imageAssetId) {
        return images.stream()
                .filter(image -> image.getId() != null && image.getId() == imageId)
                .filter(image -> image.hasUnchangedLegacySource(expectedFileKey))
                .findFirst()
                .map(image -> {
                    image.attachBackfilledAsset(imageAssetId);
                    return true;
                })
                .orElse(false);
    }

    /** legacy 메뉴 이미지 association과 나머지 메뉴 필드를 유지한 채 backfill asset만 연결한다. */
    public boolean attachBackfilledMenuImage(long menuId, String expectedImageKey, UUID imageAssetId) {
        return menus.stream()
                .filter(menu -> menu.getId() != null && menu.getId() == menuId)
                .filter(menu -> menu.hasUnchangedLegacySource(expectedImageKey))
                .findFirst()
                .map(menu -> {
                    menu.attachBackfilledAsset(imageAssetId);
                    return true;
                })
                .orElse(false);
    }

    public void replaceBusinessHours(List<RestaurantBusinessHour> businessHours) {
        this.businessHours.clear();
        if (businessHours != null) {
            businessHours.forEach(this::addBusinessHour);
        }
    }

    public void addBusinessHour(RestaurantBusinessHour businessHour) {
        businessHour.assignRestaurant(this);
        this.businessHours.add(businessHour);
    }

    private void requireActiveForLocation() {
        if (deleted) {
            throw new IllegalStateException("삭제된 식당의 위치를 요청할 수 없습니다");
        }
    }
}
