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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;
import org.sopt.hashi.BaseTimeEntity;

/**
 * 매거진 애그리거트 루트. 배너·썸네일 슬롯은 legacy S3 key 또는 public asset ID 값으로 참조한다.
 * media 엔티티 관계 없이 소속을 소유하며, 조회 URL과 파생본 응답은 Service가 구성한다.
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

    /** 상세 본문. 어드민이 입력하지 않으면 비어 있다(null). */
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

    private Magazine(String title,
                     String bannerKey, UUID bannerImageAssetId,
                     String thumbnailKey, UUID thumbnailImageAssetId,
                     String instagramRedirectUrl,
                     String content) {
        this.title = title;
        this.bannerKey = bannerKey;
        this.bannerImageAssetId = bannerImageAssetId;
        this.thumbnailKey = thumbnailKey;
        this.thumbnailImageAssetId = thumbnailImageAssetId;
        this.instagramRedirectUrl = instagramRedirectUrl;
        this.content = blankToNull(content);
        this.deleted = false;
    }

    /** 본문은 선택이다 — null이나 공백뿐인 값은 본문 없음(null)으로 저장한다. */
    public static Magazine create(String title,
                                  String bannerKey, UUID bannerImageAssetId,
                                  String thumbnailKey, UUID thumbnailImageAssetId,
                                  String instagramRedirectUrl,
                                  String content) {
        requireSource(bannerKey, bannerImageAssetId, "banner");
        requireSource(thumbnailKey, thumbnailImageAssetId, "thumbnail");
        return new Magazine(
                title,
                bannerKey, bannerImageAssetId,
                thumbnailKey, thumbnailImageAssetId,
                instagramRedirectUrl,
                content);
    }

    /**
     * Service가 claim·retire를 계획한 뒤 확정한 두 이미지 슬롯을 원자적으로 반영한다.
     * 제목·인스타그램 URL·본문은 부분 수정(PATCH)이라 null이면 기존 값을 유지한다.
     * 본문은 공백뿐인 값(빈 문자열 포함)을 보내면 지운다.
     */
    public void update(String title,
                       String bannerKey, UUID bannerImageAssetId,
                       String thumbnailKey, UUID thumbnailImageAssetId,
                       String instagramRedirectUrl,
                       String content) {
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
        if (content != null) {
            this.content = blankToNull(content);
        }
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    /** 연결 식당 ID를 노출 순서대로 돌려준다 — RestaurantPort enrich 입력용. */
    public List<Long> getRestaurantIds() {
        return restaurants.stream()
                .sorted(Comparator.comparingInt(MagazineRestaurant::getDisplayOrder))
                .map(MagazineRestaurant::getRestaurantId)
                .toList();
    }

    /** 카드뉴스를 노출 순서대로 돌려준다 — 같은 트랜잭션에서 순서를 바꾼 직후에도 순서가 맞도록 정렬해서 준다. */
    public List<MagazineCardNews> getOrderedCardNews() {
        return cardNews.stream()
                .sorted(Comparator.comparingInt(MagazineCardNews::getDisplayOrder))
                .toList();
    }

    /** 해시태그 전체 교체 — 입력 순서를 유지하고 같은 값은 한 번만 저장한다. */
    public void replaceHashtags(Collection<String> hashtags) {
        this.hashtags.clear();
        if (hashtags != null) {
            this.hashtags.addAll(hashtags);
        }
    }

    /**
     * 카드뉴스 전체 교체 계획을 세운다 — 애그리거트는 바꾸지 않는다. 목록 순서가 노출 순서다.
     * 같은 asset(또는 같은 legacy key)의 기존 행은 재사용해 순서만 바꾸고, 나머지는 새 행으로 만든다.
     * 행을 재사용하는 이유는 삭제 후 재삽입하면 flush 순서상 INSERT가 먼저 나가 image_asset_id 유니크에 걸리기 때문이다.
     */
    public CardNewsReplacement planCardNewsReplacement(List<CardNewsSource> sources) {
        List<MagazineCardNews> existing = getOrderedCardNews();
        Map<UUID, MagazineCardNews> existingByAssetId = new HashMap<>();
        Map<String, Deque<MagazineCardNews>> legacyByKey = new HashMap<>();
        for (MagazineCardNews item : existing) {
            if (item.getImageAssetId() != null) {
                existingByAssetId.put(item.getImageAssetId(), item);
            } else {
                legacyByKey.computeIfAbsent(item.getFileKey(), ignored -> new ArrayDeque<>())
                        .addLast(item);
            }
        }

        List<CardNewsReplacement.Item> items = new ArrayList<>();
        List<UUID> addedAssetIds = new ArrayList<>();
        Set<MagazineCardNews> retained = Collections.newSetFromMap(new IdentityHashMap<>());
        int displayOrder = 1;
        for (CardNewsSource source : sources) {
            MagazineCardNews reused = source.imageAssetId() != null
                    ? existingByAssetId.get(source.imageAssetId())
                    : pollFirst(legacyByKey.get(source.fileKey()));
            if (reused != null) {
                retained.add(reused);
                items.add(new CardNewsReplacement.Item(reused, displayOrder, false));
            } else if (source.imageAssetId() != null) {
                addedAssetIds.add(source.imageAssetId());
                items.add(new CardNewsReplacement.Item(
                        MagazineCardNews.createAsset(source.imageAssetId(), displayOrder),
                        displayOrder, true));
            } else {
                items.add(new CardNewsReplacement.Item(
                        MagazineCardNews.createLegacy(source.fileKey(), displayOrder),
                        displayOrder, true));
            }
            displayOrder++;
        }
        List<UUID> removedAssetIds = existing.stream()
                .filter(item -> !retained.contains(item))
                .map(MagazineCardNews::getImageAssetId)
                .filter(Objects::nonNull)
                .toList();
        return new CardNewsReplacement(items, addedAssetIds, removedAssetIds);
    }

    /** 세워 둔 계획대로 카드뉴스를 교체한다 — 빠진 행은 orphanRemoval로 삭제된다. */
    public void replaceCardNews(CardNewsReplacement replacement) {
        Set<MagazineCardNews> kept = Collections.newSetFromMap(new IdentityHashMap<>());
        replacement.items().stream()
                .filter(item -> !item.created())
                .forEach(item -> kept.add(item.cardNews()));
        cardNews.removeIf(item -> !kept.contains(item));
        for (CardNewsReplacement.Item item : replacement.items()) {
            if (item.created()) {
                item.cardNews().assignMagazine(this);
                cardNews.add(item.cardNews());
            } else {
                item.cardNews().changeDisplayOrder(item.displayOrder());
            }
        }
    }

    private MagazineCardNews pollFirst(Deque<MagazineCardNews> candidates) {
        return candidates == null ? null : candidates.pollFirst();
    }

    /**
     * 연결 식당 전체 교체 — 목록 순서가 노출 순서다. 이미 연결된 식당은 행을 재사용해 순서만 바꾼다
     * (삭제 후 재삽입하면 flush 순서상 INSERT가 먼저 나가 (magazine_id, restaurant_id) 유니크에 걸린다).
     */
    public void replaceRestaurants(List<Long> restaurantIds) {
        Map<Long, MagazineRestaurant> existingByRestaurantId = new HashMap<>();
        restaurants.forEach(item -> existingByRestaurantId.put(item.getRestaurantId(), item));
        Set<Long> requested = new LinkedHashSet<>(restaurantIds);
        restaurants.removeIf(item -> !requested.contains(item.getRestaurantId()));

        int displayOrder = 1;
        for (Long restaurantId : requested) {
            MagazineRestaurant existing = existingByRestaurantId.get(restaurantId);
            if (existing != null) {
                existing.changeDisplayOrder(displayOrder);
            } else {
                MagazineRestaurant created = MagazineRestaurant.create(restaurantId, displayOrder);
                created.assignMagazine(this);
                restaurants.add(created);
            }
            displayOrder++;
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
