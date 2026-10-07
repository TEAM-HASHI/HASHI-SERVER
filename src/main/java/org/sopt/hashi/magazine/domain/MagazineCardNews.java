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
import jakarta.persistence.UniqueConstraint;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.sopt.hashi.BaseTimeEntity;

/**
 * 매거진 상세의 카드뉴스 이미지(캐러셀 1장). 배너·썸네일과 별개로 displayOrder 순으로 노출한다.
 * 이미지는 legacy S3 key 또는 public asset ID 값으로 참조하며(식당 이미지와 같은 규격), media 엔티티 관계는 두지 않는다.
 */
@Getter
@Entity
@Table(
        name = "magazine_card_news",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_magazine_card_news_asset_id",
                columnNames = "image_asset_id"
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MagazineCardNews extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "magazine_id", nullable = false)
    private Magazine magazine;

    @Column(name = "file_key", length = 500)
    private String fileKey;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "image_asset_id", length = 36)
    private UUID imageAssetId;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    private MagazineCardNews(String fileKey, UUID imageAssetId, int displayOrder) {
        if (fileKey != null && fileKey.isBlank()) {
            throw new IllegalArgumentException("card news fileKey must not be blank");
        }
        if (fileKey == null && imageAssetId == null) {
            throw new IllegalArgumentException("card news image source is required");
        }
        this.fileKey = fileKey;
        this.imageAssetId = imageAssetId;
        changeDisplayOrder(displayOrder);
    }

    public static MagazineCardNews createLegacy(String fileKey, int displayOrder) {
        return new MagazineCardNews(Objects.requireNonNull(fileKey), null, displayOrder);
    }

    public static MagazineCardNews createAsset(UUID imageAssetId, int displayOrder) {
        return new MagazineCardNews(null, Objects.requireNonNull(imageAssetId), displayOrder);
    }

    public void changeDisplayOrder(int displayOrder) {
        if (displayOrder < 1) {
            throw new IllegalArgumentException("displayOrder must be positive");
        }
        this.displayOrder = displayOrder;
    }

    void assignMagazine(Magazine magazine) {
        this.magazine = magazine;
    }
}
