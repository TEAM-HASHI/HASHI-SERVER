package org.sopt.hashi.magazine.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.sopt.hashi.BaseTimeEntity;

/**
 * 매거진–연결 식당 매핑(architecture.md §5-2). 매거진이 소유하는 큐레이션 목록이라 자기 키는 내부 관계로 두고,
 * 식당은 타 도메인이므로 restaurant_id 값만 보관한다(FK·조인·엔티티 참조 금지). 식당 정보는 RestaurantPort로 enrich한다.
 */
@Getter
@Entity
@Table(name = "magazine_restaurant")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MagazineRestaurant extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "magazine_id", nullable = false)
    private Magazine magazine;

    @Column(name = "restaurant_id", nullable = false)
    private Long restaurantId;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    private MagazineRestaurant(Long restaurantId, int displayOrder) {
        this.restaurantId = restaurantId;
        this.displayOrder = displayOrder;
    }

    public static MagazineRestaurant create(Long restaurantId, int displayOrder) {
        return new MagazineRestaurant(restaurantId, displayOrder);
    }

    void assignMagazine(Magazine magazine) {
        this.magazine = magazine;
    }
}
