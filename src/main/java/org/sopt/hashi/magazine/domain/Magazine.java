package org.sopt.hashi.magazine.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.sopt.hashi.BaseTimeEntity;

/**
 * 매거진 애그리거트 루트. 매거진 1건당 배너 1개·썸네일 1개이며, 이미지는 S3 키(bannerKey·thumbnailKey)만
 * 저장하고 조회 URL 변환은 응답 생성 시 FileStorage가 담당한다(coding-style §4-2).
 */
@Getter
@Entity
@Table(name = "magazine")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Magazine extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "title", length = 150, nullable = false)
    private String title;

    @Column(name = "banner_key", length = 500, nullable = false)
    private String bannerKey;

    @Column(name = "thumbnail_key", length = 500, nullable = false)
    private String thumbnailKey;

    @Column(name = "instagram_redirect_url", length = 255, nullable = false)
    private String instagramRedirectUrl;

    private Magazine(String title, String bannerKey, String thumbnailKey, String instagramRedirectUrl) {
        this.title = title;
        this.bannerKey = bannerKey;
        this.thumbnailKey = thumbnailKey;
        this.instagramRedirectUrl = instagramRedirectUrl;
    }

    public static Magazine create(String title, String bannerKey, String thumbnailKey,
                                  String instagramRedirectUrl) {
        return new Magazine(title, bannerKey, thumbnailKey, instagramRedirectUrl);
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
}
