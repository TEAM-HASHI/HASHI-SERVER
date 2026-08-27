package org.sopt.hashi.restaurant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.sopt.hashi.BaseTimeEntity;

@Getter
@Entity
@Table(
        name = "restaurant_menu",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_restaurant_menu_asset_id",
                columnNames = "image_asset_id"
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RestaurantMenu extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "restaurant_id", nullable = false)
    private Restaurant restaurant;

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    @Column(name = "description", length = 500, nullable = false)
    private String description;

    @Column(name = "image_key", length = 500)
    private String imageKey;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "image_asset_id", length = 36)
    private UUID imageAssetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "price_currency", length = 3)
    private PriceCurrency priceCurrency;

    @Column(name = "price_amount", precision = 15, scale = 2)
    private BigDecimal priceAmount;

    @Column(name = "is_main", nullable = false)
    private boolean main;

    private RestaurantMenu(String name, String description, String imageKey, UUID imageAssetId,
                           PriceCurrency priceCurrency, BigDecimal priceAmount, boolean main) {
        this.name = name;
        this.description = description;
        this.imageKey = imageKey;
        this.imageAssetId = imageAssetId;
        this.priceCurrency = priceCurrency;
        this.priceAmount = priceAmount;
        this.main = main;
    }

    public static RestaurantMenu create(String name, String description, String imageKey,
                                        PriceCurrency priceCurrency, BigDecimal priceAmount,
                                        boolean main) {
        return new RestaurantMenu(
                name, description, imageKey, null, priceCurrency, priceAmount, main);
    }

    public static RestaurantMenu createWithAsset(String name, String description, UUID imageAssetId,
                                                 PriceCurrency priceCurrency, BigDecimal priceAmount,
                                                 boolean main) {
        return new RestaurantMenu(name, description, null, java.util.Objects.requireNonNull(imageAssetId),
                priceCurrency, priceAmount, main);
    }

    public void update(String name, String description, String imageKey,
                       PriceCurrency priceCurrency, BigDecimal priceAmount, boolean main) {
        update(name, description, imageKey, null, priceCurrency, priceAmount, main);
    }

    public void update(String name, String description, String imageKey, UUID imageAssetId,
                       PriceCurrency priceCurrency, BigDecimal priceAmount, boolean main) {
        this.name = name;
        this.description = description;
        this.imageKey = imageKey;
        this.imageAssetId = imageAssetId;
        this.priceCurrency = priceCurrency;
        this.priceAmount = priceAmount;
        this.main = main;
    }

    void assignRestaurant(Restaurant restaurant) {
        this.restaurant = restaurant;
    }
}
