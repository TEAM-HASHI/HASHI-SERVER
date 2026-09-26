package org.sopt.hashi.user.collection.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.sopt.hashi.BaseTimeEntity;

/**
 * 컬렉션에 저장된 식당(#216) — {@link RestaurantCollection}의 자식 매핑. 한 식당은 여러 컬렉션에 저장될 수 있지만
 * 같은 컬렉션에는 한 번만 저장된다(유니크). 식당 상세는 조회 시 RestaurantPort로 enrich한다.
 * 저장 시각은 createdAt이며 저장 순서는 id로 정한다.
 */
@Getter
@Entity
@Table(name = "saved_restaurant",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_saved_restaurant_collection_restaurant",
                columnNames = {"collection_id", "restaurant_id"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SavedRestaurant extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "collection_id", nullable = false)
    private RestaurantCollection collection;

    @Column(name = "restaurant_id", nullable = false)
    private Long restaurantId;

    private SavedRestaurant(RestaurantCollection collection, Long restaurantId) {
        this.collection = collection;
        this.restaurantId = restaurantId;
    }

    static SavedRestaurant create(RestaurantCollection collection, Long restaurantId) {
        return new SavedRestaurant(collection, restaurantId);
    }
}
