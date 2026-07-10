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
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.BatchSize;
import org.sopt.hashi.BaseTimeEntity;

@Getter
@Entity
@Table(name = "restaurant")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Restaurant extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    @Column(name = "local_name", length = 100)
    private String localName;

    @Column(name = "summary", length = 100, nullable = false)
    private String summary;

    @Column(name = "description", length = 500, nullable = false)
    private String description;

    @Column(name = "address", length = 255, nullable = false)
    private String address;

    @Column(name = "area", length = 20, nullable = false)
    private String area;

    @Enumerated(EnumType.STRING)
    @Column(name = "genre", length = 20, nullable = false)
    private RestaurantGenre genre;

    @Enumerated(EnumType.STRING)
    @Column(name = "food_category", length = 20, nullable = false)
    private RestaurantFoodCategory foodCategory;

    @Enumerated(EnumType.STRING)
    @Column(name = "price_currency", length = 3, nullable = false)
    private PriceCurrency priceCurrency;

    @Column(name = "price_min", precision = 15, scale = 2, nullable = false)
    private BigDecimal minPrice;

    @Column(name = "price_max", precision = 15, scale = 2, nullable = false)
    private BigDecimal maxPrice;

    @Column(name = "rating_sum", nullable = false)
    private long ratingSum;

    @Column(name = "review_count", nullable = false)
    private long reviewCount;

    @Column(name = "rating", precision = 2, scale = 1, nullable = false)
    private BigDecimal rating;

    @Column(name = "active", nullable = false)
    private boolean active;

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
                       String area, RestaurantGenre genre, RestaurantFoodCategory foodCategory,
                       PriceCurrency priceCurrency, BigDecimal minPrice, BigDecimal maxPrice) {
        this.name = name;
        this.localName = localName;
        this.summary = summary;
        this.description = description;
        this.address = address;
        this.area = area;
        this.genre = genre;
        this.foodCategory = foodCategory;
        this.priceCurrency = priceCurrency;
        this.minPrice = minPrice;
        this.maxPrice = maxPrice;
        this.ratingSum = 0;
        this.reviewCount = 0;
        this.rating = BigDecimal.ZERO.setScale(1);
        this.active = true;
    }

    public static Restaurant create(String name, String localName, String summary, String description,
                                    String address, String area, RestaurantGenre genre,
                                    RestaurantFoodCategory foodCategory, PriceCurrency priceCurrency,
                                    BigDecimal minPrice,
                                    BigDecimal maxPrice) {
        return new Restaurant(name, localName, summary, description, address, area, genre, foodCategory,
                priceCurrency, minPrice, maxPrice);
    }

    /** 부분 수정(PATCH) — null 필드는 기존 값을 유지한다(값 비우기 불가, magazine과 동일 정책). */
    public void updateBasicInfo(String name, String localName, String summary, String description,
                                String address, String area, RestaurantGenre genre,
                                RestaurantFoodCategory foodCategory, PriceCurrency priceCurrency,
                                BigDecimal minPrice, BigDecimal maxPrice) {
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
        if (address != null) {
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
    public void deactivate() {
        this.active = false;
    }

    public void replaceHashtags(List<String> hashtags) {
        this.hashtags.clear();
        if (hashtags != null) {
            this.hashtags.addAll(hashtags);
        }
    }

    /** 정렬 순서가 가장 빠른 식당 이미지를 대표 이미지로 사용한다. */
    public String getThumbnailFileKey() {
        return images.stream()
                .min(Comparator.comparingInt(RestaurantImage::getDisplayOrder))
                .map(RestaurantImage::getFileKey)
                .orElse(null);
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
}
