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
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
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

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "store_description", columnDefinition = "TEXT")
    private String storeDescription;

    @Column(name = "address", length = 255, nullable = false)
    private String address;

    @Column(name = "area", length = 100)
    private String area;

    @Enumerated(EnumType.STRING)
    @Column(name = "genre", length = 30, nullable = false)
    private RestaurantGenre genre;

    @Column(name = "thumbnail_file_key", length = 500)
    private String thumbnailFileKey;

    @Column(name = "reservation_fee", nullable = false)
    private long reservationFee;

    @Column(name = "currency", length = 10, nullable = false)
    private String currency;

    @Column(name = "min_price", precision = 15, scale = 2)
    private BigDecimal minPrice;

    @Column(name = "max_price", precision = 15, scale = 2)
    private BigDecimal maxPrice;

    @Column(name = "rating", nullable = false)
    private double rating;

    @Column(name = "review_count", nullable = false)
    private long reviewCount;

    @Column(name = "saved_count", nullable = false)
    private long savedCount;

    @Column(name = "popularity_score", nullable = false)
    private long popularityScore;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "available_date")
    private LocalDate availableDate;

    @Column(name = "available_start_time")
    private LocalTime availableStartTime;

    @Column(name = "available_end_time")
    private LocalTime availableEndTime;

    @BatchSize(size = 100)
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "restaurant_tag", joinColumns = @JoinColumn(name = "restaurant_id"))
    @Column(name = "tag", length = 50, nullable = false)
    private Set<String> tags = new LinkedHashSet<>();

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

    private Restaurant(String name, String localName, String description, String storeDescription, String address,
                       String area,
                       RestaurantGenre genre, String thumbnailFileKey, long reservationFee, String currency,
                       BigDecimal minPrice, BigDecimal maxPrice) {
        this.name = name;
        this.localName = localName;
        this.description = description;
        this.storeDescription = storeDescription;
        this.address = address;
        this.area = area;
        this.genre = genre;
        this.thumbnailFileKey = thumbnailFileKey;
        this.reservationFee = reservationFee;
        this.currency = currency;
        this.minPrice = minPrice;
        this.maxPrice = maxPrice;
        this.rating = 0.0;
        this.reviewCount = 0;
        this.savedCount = 0;
        this.popularityScore = 0;
        this.active = true;
    }

    public static Restaurant create(String name, String localName, String description, String address, String area,
                                    RestaurantGenre genre, String thumbnailFileKey, long reservationFee,
                                    String currency, BigDecimal minPrice, BigDecimal maxPrice) {
        return create(name, localName, description, null, address, area, genre, thumbnailFileKey,
                reservationFee, currency, minPrice, maxPrice);
    }

    public static Restaurant create(String name, String localName, String description, String storeDescription,
                                    String address, String area, RestaurantGenre genre, String thumbnailFileKey,
                                    long reservationFee, String currency, BigDecimal minPrice,
                                    BigDecimal maxPrice) {
        return new Restaurant(name, localName, description, storeDescription, address, area, genre, thumbnailFileKey,
                reservationFee, currency, minPrice, maxPrice);
    }

    /** 부분 수정(PATCH) — null 필드는 기존 값을 유지한다(값 비우기 불가, magazine과 동일 정책). */
    public void updateBasicInfo(String name, String localName, String description, String storeDescription,
                                String address, String area, RestaurantGenre genre, String thumbnailFileKey,
                                Long reservationFee, String currency, BigDecimal minPrice, BigDecimal maxPrice) {
        if (name != null) {
            this.name = name;
        }
        if (localName != null) {
            this.localName = localName;
        }
        if (description != null) {
            this.description = description;
        }
        if (storeDescription != null) {
            this.storeDescription = storeDescription;
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
        if (thumbnailFileKey != null) {
            this.thumbnailFileKey = thumbnailFileKey;
        }
        if (reservationFee != null) {
            this.reservationFee = reservationFee;
        }
        if (currency != null) {
            this.currency = currency;
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

    public void replaceTags(List<String> tags) {
        this.tags.clear();
        if (tags != null) {
            this.tags.addAll(tags);
        }
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
