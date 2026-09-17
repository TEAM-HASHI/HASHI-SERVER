package org.sopt.hashi.magazine.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;
import org.sopt.hashi.BaseTimeEntity;

/**
 * 매거진 애그리거트 루트. 배너·썸네일 슬롯은 legacy S3 key 또는 public asset ID 값으로 참조한다.
 * media 엔티티 관계 없이 소속을 소유하며, 조회 URL과 파생본 응답은 Service가 구성한다.
 * 삭제는 soft delete(deleted=true)이며, 삭제된 매거진은 전역 필터로 모든 조회에서 제외된다.
 */
@Getter
@Entity
@Table(name = "magazine")
@SQLDelete(sql = "UPDATE magazine SET deleted = true WHERE id = ?")
@SQLRestriction("deleted = false")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Magazine extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "title", length = 150, nullable = false)
    private String title;

    @Column(name = "banner_key", length = 500)
    private String bannerKey;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "banner_image_asset_id", length = 36, unique = true)
    private UUID bannerImageAssetId;

    @Column(name = "thumbnail_key", length = 500)
    private String thumbnailKey;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "thumbnail_image_asset_id", length = 36, unique = true)
    private UUID thumbnailImageAssetId;

    @Column(name = "instagram_redirect_url", length = 255, nullable = false)
    private String instagramRedirectUrl;

    @Column(name = "deleted", nullable = false)
    private boolean deleted;

    private Magazine(String title,
                     String bannerKey, UUID bannerImageAssetId,
                     String thumbnailKey, UUID thumbnailImageAssetId,
                     String instagramRedirectUrl) {
        this.title = title;
        this.bannerKey = bannerKey;
        this.bannerImageAssetId = bannerImageAssetId;
        this.thumbnailKey = thumbnailKey;
        this.thumbnailImageAssetId = thumbnailImageAssetId;
        this.instagramRedirectUrl = instagramRedirectUrl;
        this.deleted = false;
    }

    public static Magazine create(String title, String bannerKey, String thumbnailKey,
                                  String instagramRedirectUrl) {
        return create(title, bannerKey, null, thumbnailKey, null, instagramRedirectUrl);
    }

    public static Magazine create(String title,
                                  String bannerKey, UUID bannerImageAssetId,
                                  String thumbnailKey, UUID thumbnailImageAssetId,
                                  String instagramRedirectUrl) {
        requireSource(bannerKey, bannerImageAssetId, "banner");
        requireSource(thumbnailKey, thumbnailImageAssetId, "thumbnail");
        return new Magazine(
                title,
                bannerKey, bannerImageAssetId,
                thumbnailKey, thumbnailImageAssetId,
                instagramRedirectUrl);
    }

    /** 부분 수정(PATCH) — null 필드는 기존 값을 유지한다. */
    public void update(String title, String bannerKey, String thumbnailKey, String instagramRedirectUrl) {
        update(
                title,
                bannerKey == null ? this.bannerKey : bannerKey,
                bannerKey == null ? this.bannerImageAssetId : null,
                thumbnailKey == null ? this.thumbnailKey : thumbnailKey,
                thumbnailKey == null ? this.thumbnailImageAssetId : null,
                instagramRedirectUrl);
    }

    /** Service가 claim·retire를 계획한 뒤 확정한 두 이미지 슬롯을 원자적으로 반영한다. */
    public void update(String title,
                       String bannerKey, UUID bannerImageAssetId,
                       String thumbnailKey, UUID thumbnailImageAssetId,
                       String instagramRedirectUrl) {
        requireSource(bannerKey, bannerImageAssetId, "banner");
        requireSource(thumbnailKey, thumbnailImageAssetId, "thumbnail");
        if (title != null) {
            this.title = title;
        }
        this.bannerKey = bannerKey;
        this.bannerImageAssetId = bannerImageAssetId;
        this.thumbnailKey = thumbnailKey;
        this.thumbnailImageAssetId = thumbnailImageAssetId;
        if (instagramRedirectUrl != null) {
            this.instagramRedirectUrl = instagramRedirectUrl;
        }
    }

    /** 잠금 아래 조사한 legacy 배너가 그대로일 때 UUID만 연결하고 다른 필드를 보존한다. */
    public boolean attachBackfilledBanner(String expectedKey, UUID assetId) {
        requireBackfillAsset(assetId);
        if (deleted || bannerKey == null || bannerKey.isBlank()
                || bannerImageAssetId != null || !bannerKey.equals(expectedKey)) {
            return false;
        }
        bannerImageAssetId = assetId;
        return true;
    }

    /** 배너와 독립된 썸네일 슬롯이며 기존 key와 표시 정보를 유지한다. */
    public boolean attachBackfilledThumbnail(String expectedKey, UUID assetId) {
        requireBackfillAsset(assetId);
        if (deleted || thumbnailKey == null || thumbnailKey.isBlank()
                || thumbnailImageAssetId != null || !thumbnailKey.equals(expectedKey)) {
            return false;
        }
        thumbnailImageAssetId = assetId;
        return true;
    }

    private static void requireBackfillAsset(UUID assetId) {
        if (assetId == null) {
            throw new IllegalArgumentException("backfill asset ID is required");
        }
    }

    private static void requireSource(String key, UUID assetId, String slot) {
        if (key == null && assetId == null) {
            throw new IllegalArgumentException(slot + " image source is required");
        }
    }
}
