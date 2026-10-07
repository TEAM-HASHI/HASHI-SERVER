package org.sopt.hashi.support.domain;

import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.sopt.hashi.BaseTimeEntity;

@Getter
@Entity
@Table(name = "support_notice")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notice extends BaseTimeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(name = "body_json", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String bodyJson;

    @Column(nullable = false)
    private boolean deleted;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "modified_after_publication_at")
    private LocalDateTime modifiedAfterPublicationAt;

    @org.hibernate.annotations.BatchSize(size = 50)
    @ElementCollection
    @CollectionTable(name = "support_notice_image", joinColumns = @JoinColumn(name = "notice_id"))
    @OrderColumn(name = "display_order")
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "image_asset_id", length = 36, nullable = false)
    private List<UUID> imageAssetIds = new ArrayList<>();

    public Notice(String title, String bodyJson, List<UUID> images) {
        this.title = title;
        this.bodyJson = bodyJson;
        this.imageAssetIds.addAll(images);
    }

    public void update(String title, String bodyJson, List<UUID> images, LocalDateTime now, boolean imagesChanged) {
        boolean changed = !this.title.equals(title) || !this.bodyJson.equals(bodyJson)
                || imagesChanged;
        this.title = title;
        this.bodyJson = bodyJson;
        imageAssetIds.clear();
        imageAssetIds.addAll(images);
        if (changed) {
            markUpdatedAt(now);
            if (publishedAt != null) {
                modifiedAfterPublicationAt = now;
            }
        }
    }

    public void clearImages() { imageAssetIds.clear(); }

    public void publish(LocalDateTime now) {
        if (publishedAt == null) {
            publishedAt = now;
        }
    }

    public void delete() {
        deleted = true;
        imageAssetIds.clear();
    }

    public LocalDateTime lastModifiedAt() {
        return modifiedAfterPublicationAt == null ? publishedAt : modifiedAfterPublicationAt;
    }
}
