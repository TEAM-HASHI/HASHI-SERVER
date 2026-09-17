package org.sopt.hashi.magazine.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import org.sopt.hashi.BaseTimeEntity;

/**
 * 매거진 애그리거트 루트. 매거진 1건당 배너 1개·썸네일 1개이며, 이미지는 S3 키(bannerKey·thumbnailKey)만
 * 저장하고 조회 URL 변환은 응답 생성 시 FileStorage가 담당한다(coding-style §4-2).
 * 상세 화면의 카드뉴스·해시태그·연결 식당은 애그리거트 자식으로 두고, 연결 식당은 타 도메인이라
 * restaurant_id 값만 보관한다(architecture.md §5-2). 좋아요 수 같은 집계는 {@link MagazineMeta}가 담당한다.
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

    @Column(name = "banner_key", length = 500, nullable = false)
    private String bannerKey;

    @Column(name = "thumbnail_key", length = 500, nullable = false)
    private String thumbnailKey;

    @Column(name = "instagram_redirect_url", length = 255, nullable = false)
    private String instagramRedirectUrl;

    /** 상세 본문. 어드민 등록 계약이 확정되기 전까지는 비어 있을 수 있다. */
    @Column(name = "content", length = 2000)
    private String content;

    @Column(name = "deleted", nullable = false)
    private boolean deleted;

    @BatchSize(size = 100)
    @OrderBy("displayOrder ASC")
    @OneToMany(mappedBy = "magazine", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<MagazineCardNews> cardNews = new ArrayList<>();

    @BatchSize(size = 100)
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "magazine_hashtag", joinColumns = @JoinColumn(name = "magazine_id"))
    @Column(name = "hashtag", length = 20, nullable = false)
    private Set<String> hashtags = new LinkedHashSet<>();

    @BatchSize(size = 100)
    @OrderBy("displayOrder ASC")
    @OneToMany(mappedBy = "magazine", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<MagazineRestaurant> restaurants = new ArrayList<>();

    private Magazine(String title, String bannerKey, String thumbnailKey, String instagramRedirectUrl) {
        this.title = title;
        this.bannerKey = bannerKey;
        this.thumbnailKey = thumbnailKey;
        this.instagramRedirectUrl = instagramRedirectUrl;
        this.deleted = false;
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

    /** 연결 식당 ID를 노출 순서대로 돌려준다 — RestaurantPort enrich 입력용. */
    public List<Long> getRestaurantIds() {
        return restaurants.stream()
                .map(MagazineRestaurant::getRestaurantId)
                .toList();
    }
}
