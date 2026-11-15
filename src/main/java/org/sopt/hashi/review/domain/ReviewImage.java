package org.sopt.hashi.review.domain;

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
import org.sopt.hashi.BaseTimeEntity;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Entity
@Table(name = "review_image", uniqueConstraints = @UniqueConstraint(
        name = "uq_review_image_asset_id", columnNames = "image_asset_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReviewImage extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "review_id", nullable = false)
    private Review review;

    @Column(name = "file_key", length = 500)
    private String fileKey;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "image_asset_id", length = 36)
    private UUID imageAssetId;

    // 순서 교환 중 같은 transaction의 첫 flush에서만 null을 사용한다.
    @Column(name = "display_order")
    private Integer displayOrder;

    private ReviewImage(String fileKey, int displayOrder) {
        this.fileKey = fileKey;
        setDisplayOrder(displayOrder);
    }

    public static ReviewImage create(String fileKey, int displayOrder) {
        return new ReviewImage(fileKey, displayOrder);
    }

    public static ReviewImage createAsset(UUID imageAssetId, int displayOrder) {
        ReviewImage image = new ReviewImage(null, displayOrder);
        image.imageAssetId = Objects.requireNonNull(imageAssetId);
        return image;
    }

    void stageOrderChange() {
        this.displayOrder = null;
    }

    void setDisplayOrder(int displayOrder) {
        if (displayOrder < 0 || displayOrder > 9) {
            throw new IllegalArgumentException("review displayOrder must be between 0 and 9");
        }
        this.displayOrder = displayOrder;
    }

    void assignReview(Review review) {
        this.review = review;
    }
}
