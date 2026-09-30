package org.sopt.hashi.user.collection.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.BatchSize;
import org.sopt.hashi.BaseTimeEntity;

/**
 * 식당 컬렉션(#216) — 회원이 식당을 묶어 저장하는 단위(SAVED-004~008). user 애그리거트의 하위 도메인이라
 * 소유자는 user_id 값으로만 보관하고, 저장 식당({@link SavedRestaurant})은 이 애그리거트의 자식이다.
 * 식당은 타 애그리거트라 restaurant_id 값만 보관한다(architecture.md §5-2). 컬렉션 삭제는 저장 관계만 지운다.
 */
@Getter
@Entity
@Table(name = "restaurant_collection",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_restaurant_collection_user_name", columnNames = {"user_id", "name"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RestaurantCollection extends BaseTimeEntity {

    public static final int NAME_MAX_LENGTH = 20;
    public static final int DESCRIPTION_MAX_LENGTH = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "name", length = NAME_MAX_LENGTH, nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "color", length = 20, nullable = false)
    private CollectionColor color;

    @Column(name = "description", length = DESCRIPTION_MAX_LENGTH)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", length = 20, nullable = false)
    private CollectionVisibility visibility;

    /** 저장 순서(id 오름차순) — 커버 이미지는 오래된 저장부터, 최신순 목록은 그 역순이다. */
    @BatchSize(size = 100)
    @OrderBy("id ASC")
    @OneToMany(mappedBy = "collection", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SavedRestaurant> savedRestaurants = new ArrayList<>();

    private RestaurantCollection(Long userId, String name, CollectionColor color, String description,
                                 CollectionVisibility visibility) {
        this.userId = userId;
        this.name = name;
        this.color = color;
        this.description = description;
        this.visibility = visibility;
    }

    public static RestaurantCollection create(Long userId, String name, CollectionColor color,
                                              String description, CollectionVisibility visibility) {
        return new RestaurantCollection(userId, name, color, description, visibility);
    }

    /** 부분 수정(PATCH) — null 필드는 기존 값을 유지한다(식당·매거진과 동일 정책). 설명은 빈 문자열로 비울 수 있다. */
    public void update(String name, CollectionColor color, String description, CollectionVisibility visibility) {
        if (name != null) {
            this.name = name;
        }
        if (color != null) {
            this.color = color;
        }
        if (description != null) {
            this.description = description.isBlank() ? null : description;
        }
        if (visibility != null) {
            this.visibility = visibility;
        }
    }

    public boolean isOwnedBy(Long userId) {
        return this.userId.equals(userId);
    }

    public boolean isPublic() {
        return visibility == CollectionVisibility.PUBLIC;
    }

    public boolean contains(Long restaurantId) {
        return findSaved(restaurantId).isPresent();
    }

    public Optional<SavedRestaurant> findSaved(Long restaurantId) {
        return savedRestaurants.stream()
                .filter(saved -> saved.getRestaurantId().equals(restaurantId))
                .findFirst();
    }

    /** 저장 식당 추가 — 같은 식당 중복 저장 여부는 호출 측(서비스)이 먼저 검사한다(DB 유니크가 최종 방어선). */
    public SavedRestaurant save(Long restaurantId) {
        SavedRestaurant saved = SavedRestaurant.create(this, restaurantId);
        savedRestaurants.add(saved);
        return saved;
    }

    public void remove(Collection<Long> restaurantIds) {
        Set<Long> targets = Set.copyOf(restaurantIds);
        savedRestaurants.removeIf(saved -> targets.contains(saved.getRestaurantId()));
    }

    public int savedRestaurantCount() {
        return savedRestaurants.size();
    }
}
