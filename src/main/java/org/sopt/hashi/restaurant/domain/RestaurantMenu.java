package org.sopt.hashi.restaurant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.sopt.hashi.BaseTimeEntity;

@Getter
@Entity
@Table(name = "restaurant_menu")
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

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "image_file_key", length = 500)
    private String imageFileKey;

    @Column(name = "currency", length = 10, nullable = false)
    private String currency;

    @Column(name = "price", precision = 15, scale = 2)
    private BigDecimal price;

    @Column(name = "representative", nullable = false)
    private boolean representative;

    private RestaurantMenu(String name, String description, String imageFileKey,
                           String currency, BigDecimal price, boolean representative) {
        this.name = name;
        this.description = description;
        this.imageFileKey = imageFileKey;
        this.currency = currency;
        this.price = price;
        this.representative = representative;
    }

    public static RestaurantMenu create(String name, String description, String imageFileKey,
                                        String currency, BigDecimal price, boolean representative) {
        return new RestaurantMenu(name, description, imageFileKey, currency, price, representative);
    }

    void assignRestaurant(Restaurant restaurant) {
        this.restaurant = restaurant;
    }
}
