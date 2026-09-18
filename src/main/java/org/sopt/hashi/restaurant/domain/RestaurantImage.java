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
import jakarta.persistence.UniqueConstraint;
import java.util.Objects;
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
        name = "restaurant_image",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_restaurant_image_asset_id",
                columnNames = "image_asset_id"
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RestaurantImage extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "restaurant_id", nullable = false)
    private Restaurant restaurant;

    @Column(name = "file_key", length = 500)
    private String fileKey;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "image_asset_id", length = 36)
    private UUID imageAssetId;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    private RestaurantImage(String fileKey, UUID imageAssetId, int displayOrder) {
        if (fileKey != null && fileKey.isBlank()) {
            throw new IllegalArgumentException("restaurant image fileKey must not be blank");
        }
        if (fileKey == null && imageAssetId == null) {
            throw new IllegalArgumentException("restaurant image source is required");
        }
        this.fileKey = fileKey;
        this.imageAssetId = imageAssetId;
        setDisplayOrder(displayOrder);
    }

    public static RestaurantImage createLegacy(String fileKey, int displayOrder) {
        return new RestaurantImage(fileKey, null, displayOrder);
    }

    public static RestaurantImage createAsset(UUID imageAssetId, int displayOrder) {
        return new RestaurantImage(null, Objects.requireNonNull(imageAssetId), displayOrder);
    }

    public static RestaurantImage createBackfilled(
            String fileKey,
            UUID imageAssetId,
            int displayOrder
    ) {
        return new RestaurantImage(fileKey, Objects.requireNonNull(imageAssetId), displayOrder);
    }

    /** legacy 호출부를 점진 전환하는 동안 유지하는 생성 함수. */
    public static RestaurantImage create(String fileKey, int displayOrder) {
        return createLegacy(fileKey, displayOrder);
    }

    public void setDisplayOrder(int displayOrder) {
        if (displayOrder < 1) {
            throw new IllegalArgumentException("displayOrder must be positive");
        }
        this.displayOrder = displayOrder;
    }

    void assignRestaurant(Restaurant restaurant) {
        this.restaurant = restaurant;
    }
}
