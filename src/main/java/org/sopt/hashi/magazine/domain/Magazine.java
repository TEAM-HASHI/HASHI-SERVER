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
 * 매거진 애그리거트 루트. 매거진 1건당 배너 1개·썸네일 1개이며, 이미지는 S3 키(bannerKey·thumbnailKey)만
 * 저장하고 조회 URL 변환은 응답 생성 시 FileStorage가 담당한다(coding-style §4-2).
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
        if (title != null) {
            this.title = title;
        }
        if (bannerKey != null) {
            this.bannerKey = bannerKey;
        }
        if (thumbnailKey != null) {
            this.thumbnailKey = thumbnailKey;
        }
        if (instagramRedirectUrl != null) {
            this.instagramRedirectUrl = instagramRedirectUrl;
        }
    }

    private static void requireSource(String key, UUID assetId, String slot) {
        if (key == null && assetId == null) {
            throw new IllegalArgumentException(slot + " image source is required");
        }
    }
}
